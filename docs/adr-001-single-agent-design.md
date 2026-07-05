# ADR-001: Single-Agent Design for BPMN Generation

## Status

Accepted (epic #399, finalised by sub-issue #409, 2026-06-15)

## Context

Before epic #399 the pipeline was a chain of six per-stage `@Agent` classes
(`BpmnGenerationGateAgent`, contract, generator, repair, alignment, plus
`BpmnLayoutAgent`). Each stage exposed its own `@AchievesGoal` export and the
GOAP planner chained them at the whole-platform level.

This created two structural problems:

1. **The static GOAP validator could not prove a complete path.** With multiple
   competing goals the `GoapPathToCompletionValidator` had to assume any agent
   could satisfy any goal; provability required either an exhaustive reachability
   proof across all permutations or a bespoke solver — neither of which existed.

2. **Closed-mode shell composition (`x` / `execute`) was fragile.** Embabel's
   `x` command composes in *closed mode*: it seeds the blackboard with the
   user's `UserInput` and lets the planner reach the configured goal in one
   bounded plan. A multi-agent goal set forced the planner to stitch agents
   together at the top level, producing implicit orchestration that was hard to
   trace and impossible to budget uniformly.

## Decision

Consolidate the generation pipeline into **one `@Agent`** with **one
`@AchievesGoal`**:

- `BpmnGenerationAgent` owns the `generateBpmn` goal (achieved on `finish`, or on validation/readiness failure via `ValidationFailed.terminate`/`Blocked.terminate`).
- All pipeline stages are thin `@Action` methods that delegate to per-module
  **ports**. Modules are capability libraries, not agents.
- Inline prompts and few-shot examples live in module-owned `PromptContributor`
  beans; no prompt lives directly on the orchestrator.

Three structural sub-decisions follow from the single-agent constraint:

### G2 — Modules are ports, not agents

Each module (`contract/`, `generation/`, `repair/`, `alignment/`, `layout/`)
exposes a `@PrimaryPort` interface. The orchestrator's actions take the port as a
constructor dependency and call it; the real work lives behind the port in a plain
Spring `@Component`. This makes each module testable in isolation without standing
up the agent platform.

### G3 — Readiness and clarification are a `@State` machine

Readiness assessment is **not** a throwing gate. The orchestrator runs a `@State`
machine (`Assessing → Ready | AwaitingClarification | Blocked`) that loops via
`clearBlackboard = true`. The round counter is carried explicitly in the state
record. In web/INTERACTIVE mode, `AwaitingClarification.ask()` pauses the process
via `WaitFor.formSubmission`, surfacing clarification as an SSE event — no HTTP 422
branch exists.

The readiness *assessment* itself runs as a **scoped sub-process** bound to
`BpmnReadinessAgent`. Scoping is load-bearing: an unscoped sub-process for
`ProcessInputAssessment` would also match the orchestrator's own `assessReadiness`
action (which calls the invoker), creating unbounded recursion. The sub-agent is
resolved by name (`READINESS_AGENT_NAME`) and is **not** exposed remotely.

### G4 — Repair is `RepeatUntilAcceptable`

The orchestrator's `validate` action delegates to the `BpmnRepairer` port
(`DefaultBpmnRepairer`), which drives an iterative `RepeatUntilAcceptable` loop
(`BpmnRepairLoop`) over cost-tiered repair appliers. The loop exits when all
blocking diagnostics are resolved or the iteration budget is exhausted. If unresolved
blocking diagnostics remain after repair-loop exhaustion, the orchestrator immediately
fails the pipeline via `ValidationFailed.terminate`, short-circuiting layout and alignment.
This keeps repair logic in `repair/`, not scattered across the orchestrator.

### G5 — Alignment is a critique gate

The `align` action returns a `BpmnAlignmentReport` — it does **not** throw on
`verdict == FAILED`. The `finish` action reads the report: if `verdict == FAILED`
it returns `BpmnResult(status = ALIGNMENT_FAILED)` without writing a file;
otherwise it writes the output and returns `BpmnResult(status = GENERATED)`.
Treating alignment as a critique gate keeps the failure mode typed and avoids
collapsing alignment failures with framework exceptions.

### G6 — Web uses the Tripper `JourneyController` pattern

The web entrypoint (`BpmnWebController` → `WebGenerationStarter`) calls the
web-only `BpmnAgentInvoker.startAsync(request)` overload. It sets the process mode
to `INTERACTIVE` and returns `202 {processId, sseUrl}`. There is no synchronous
readiness pre-check and no HTTP 422 branch. Clarification surfaces as an in-process
`WaitFor.formSubmission` over SSE, reachable because the agent starts
`INTERACTIVE`. The synchronous `generate(request, assessment)` and legacy
`startAsync(request, assessment)` overloads remain intact for the CLI/shell seam.

## Why GOAP, not Utility-AI or a bespoke state machine

- **Type-driven composition.** Adding a pipeline stage is adding a typed `@Action`
  and a port; the planner threads it by input/output type with no orchestrator
  rewiring. No explicit routing table or conditional dispatch exists.
- **Observable, typed failure modes.** STUCK (`ProcessExecutionStuckException`)
  and TERMINATED (`ProcessExecutionTerminatedException`) are distinct and surface
  through `AgentProcessExecution.fromProcessStatus()`; a bespoke exception would
  conflate "no path" with "out of budget."
- **Per-action retry policy.** Terminal actions (`layout`, `align`, `finish`,
  `reassess`) declare `ActionRetryPolicy.FIRE_ONCE` so a model failure there is
  not silently retried.
- **Scoped sub-processes.** Readiness runs as its own bounded process with its
  own budget (`bpmner.budget.readiness`, default `20`), isolated from the main
  plan's goal set and budget ceiling.
- **Static provability.** One agent, one goal: `GoapPathToCompletionValidator`
  can verify a complete path to `generateBpmn` at boot time without speculative
  inter-agent reasoning. The boot gate (`AgentDeploymentValidationBootTest`) asserts
  this on every startup.

## Deployed agents (deliberate exceptions)

The epic slogan is "one agent" but **three `@Agent`s are deployed**, and the boot
validator pins exactly that roster:

| Agent | Role | Remote export? |
| --- | --- | --- |
| `BpmnGenerationAgent` | The orchestrator. Owns `generateBpmn`. | No |
| `BpmnReadinessAgent` | Scoped sub-agent for readiness. Invoked by name. | No |
| `BpmnLayoutAgent` | Vestigial standalone layout goal (`finalizeLayout`). Orchestrator bypasses it. | No |

`BpmnReadinessAgent` is a deliberate sub-agent bridge (G3 above). Its `remote`
export was removed in #409 — local by-name resolution via `READINESS_AGENT_NAME`
is independent of the remote flag.

`BpmnLayoutAgent` is vestigial: the orchestrator's `layout` action calls
`BpmnLayoutPort` directly (`BpmnGenerationAgent.kt:116-117`) and inlines the XSD
check. The layout agent is kept deployed to avoid a 5-part atomic change on the
epic's final PR (boot validator roster, test, exception, observer labels, docs).
Its `remote` export was removed in #409. Deletion is tracked as a future
non-epic cleanup.

`remote = true` was removed from both sub-agent exports in #409. No agent
in the deployed roster publishes a remote/MCP tool surface (N2).

## Consequences

- The static GOAP validator is provable at boot for every deployed agent (gate 1).
- Shell `x` composes natively in closed mode with `UserInput` as the seed.
- Readiness, clarification, repair, and alignment are each encapsulated in their
  own module, testable without the agent platform.
- Documentation (`agents.md`, `goap-lifecycle.md`, `pipeline-architecture.md`,
  `hexagonal-architecture.md`) reflects the post-#408 as-built contract.
- `BpmnLayoutAgent` remains deployed but inert; its removal is a separate issue.
