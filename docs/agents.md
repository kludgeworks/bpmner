# Agent Overview

bpmner is built around a single orchestrating Embabel `@Agent` plus two narrow support agents. The orchestrator owns the end-to-end `generateBpmn` goal; each of its `@Action` methods is a thin shim that delegates to a public **port**, and the real work lives behind those ports in plain Spring components. This is a deliberate change from the earlier design, where every pipeline stage was its own `@Agent` chained by the GOAP planner.

> Historical note: the per-stage agents (`BpmnGenerationGateAgent`, contract, generator, repair, alignment) were consolidated into the single `BpmnGenerationAgent` orchestrator. The names below are the only agents that exist today.

For the planner mechanics — how the orchestrator's actions are chained, cost ordering, failure modes — see [`goap-lifecycle.md`](./goap-lifecycle.md).

## The three agents

| Agent | File | Actions | Achieves goal | Notes |
| --- | --- | --- | --- | --- |
| `BpmnGenerationAgent` | `orchestration/internal/adapter/inbound/BpmnGenerationAgent.kt` | 13 typed shims: `draft`, `resolve`, `assessReadiness`, `startAssessing`, `extractContract`, `createOutline`, `composeGraph`, `render`, `validate`, `layout`, `align`, `finish`, `reassess` | `generateBpmn` (on `finish`) | The single orchestrator. Each action delegates to a public port; `finish` writes the output file and returns `BpmnResult`. |
| `BpmnReadinessAgent` | `readiness/internal/adapter/inbound/BpmnReadinessAgent.kt` | 1: `assessReadiness` (`BpmnRequest → ProcessInputAssessment`) | `assessReadiness` | Invoked as a **scoped sub-process** by the orchestrator's `assessReadiness` action, not chained into the main plan. |
| `BpmnLayoutAgent` | `layout/internal/adapter/inbound/BpmnLayoutAgent.kt` | 2: `layoutBpmnXml`, `validateFinalBpmnXml` | `finalizeLayout` | Standalone layout agent (GraalJS auto-layout + XSD validation). **Not** used by the orchestrator's `layout` action, which does layout inline — see below. |

The `BpmnProgressProjectionObserver` maps action names to user-facing labels — see [`operator-guide.md`](./operator-guide.md#progress-events-sse).

## `BpmnGenerationAgent` — the orchestrator

`BpmnGenerationAgent` (`@Agent(description = "Single idiomatic agent for happy-path BPMN generation")`) is constructed from nine ports and exposes thirteen `@Action` methods. The planner threads them by type from a starting `UserInput` (shell) or `BpmnRequest` (web, INTERACTIVE mode via the web-only `startAsync(request)` overload) through to a `BpmnResult`. The legacy `BpmnRequest` + `ProcessInputAssessment` pair is the CLI/shell seam and is left intact.

Each action delegates to a public port; the port is implemented as a plain Spring component in its owning module (the LLM-backed ones are `@PrimaryAdapter @Component` under each module's `internal/adapter/inbound/`):

| Action | Input → Output | Port | Implementation |
| --- | --- | --- | --- |
| `draft` | `(UserInput, OperationContext) → BpmnRequestDraft` | `BpmnRequestDrafter` | `LlmBpmnRequestDrafter` (`generation/`) — LLM extracts a structured draft from shell prose. |
| `resolve` | `BpmnRequestDraft → BpmnRequest` | `BpmnRequestResolver` | `BpmnRequestResolver` (`generation/`) — deterministic: resolves description/style-guide/output paths via `InputPathResolver`. |
| `assessReadiness` | `BpmnRequest → ProcessInputAssessment` | `BpmnReadinessInvoker` | `AgentPlatformBpmnReadinessInvoker` (`readiness/`) — runs `BpmnReadinessAgent` as a scoped sub-process. |
| `startAssessing` | `(BpmnRequest, ProcessInputAssessment) → Assessing` | (inline) | Wraps request + assessment into `Assessing` state for the `@State` machine. |
| `extractContract` | `(ReadyBpmnContext, OperationContext) → ValidatedProcessContract` | `ProcessContractExtractor` | `LlmProcessContractExtractor` (`contract/`) — distils a source-grounded `ProcessContract`. |
| `createOutline` | `(ReadyBpmnContext, ValidatedProcessContract, OperationContext) → ValidatedOutline` | `BpmnProcessGenerator` | `LlmBpmnProcessGenerator` (`generation/`) — LLM produces a typed `BpmnDefinition` outline. |
| `composeGraph` | `ValidatedOutline → LaidOutProcessGraph` | `BpmnProcessGenerator` | `LlmBpmnProcessGenerator` — deterministic composition/ownership. |
| `render` | `(ReadyBpmnContext, LaidOutProcessGraph) → RenderedBpmn` | `BpmnProcessGenerator` | `LlmBpmnProcessGenerator`, which delegates rendering to the domain `@Component` `BpmnGraphRenderer` (holds the `BpmnRenderer` secondary port). |
| `validate` | `(ReadyBpmnContext, LaidOutProcessGraph, RenderedBpmn, ValidatedProcessContract) → ValidatedBpmnXml` | `BpmnRepairer` | `DefaultBpmnRepairer` (`repair/`) — **validate-only**: a single validation pass (`BpmnRepairAdvancer.initialEvaluation`). |
| `layout` | `ValidatedBpmnXml → FinalValidatedBpmnXml` | (inline) | Calls `BpmnLayoutPort` + `BpmnXsdValidationPort` directly; `error(...)`s on XSD-invalid output. Does **not** route through `BpmnLayoutAgent`. |
| `align` | `(ReadyBpmnContext, ValidatedProcessContract, FinalValidatedBpmnXml, OperationContext) → BpmnAlignmentReport` | `BpmnAligner` | `LlmBpmnAligner` (`alignment/`) — semantic comparison vs the contract. Returns a report; does **not** throw on verdict. `FIRE_ONCE`. |
| `finish` | `(ReadyBpmnContext, FinalValidatedBpmnXml, BpmnAlignmentReport) → BpmnResult` | (inline) | Critique gate: if `verdict == FAILED` → `BpmnResult(status = ALIGNMENT_FAILED)`, no file write. Otherwise writes the output file (mkdirs parent, skips blank paths), returns `BpmnResult(status = GENERATED)`. Carries `@AchievesGoal(name = "generateBpmn")`. `FIRE_ONCE`. |
| `reassess` | `(AwaitingClarification, BpmnClarificationAnswers) → Assessing` | (inline) | Loops back into `Assessing` after a clarification answer; updates request and increments round counter. `clearBlackboard = true`. |

The `layout`, `align`, `finish`, and `reassess` actions are annotated `actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE`.

### `@State` machine

The orchestrator uses a `@State` machine for readiness/clarification loops. The machine state methods (`assess`, `ask`, `proceed`, `terminate`) live inside the state records as nested `@Action`s; `reassess` is an outer-class method that loops back into `Assessing`.

The state machine actions `assess`, `ask`, `proceed`, and `terminate` all share the same `generateBpmn` goal as their enclosing agent. `terminate` in the `Blocked` state produces a `BpmnResult(status = NEEDS_CLARIFICATION)` to indicate the process cannot proceed without user input. The `clearBlackboard = true` flag on `assess` and `reassess` ensures the loop re-enters the machine with fresh context after each clarification answer.

| State | Action | Input → Output | What happens |
| --- | --- | --- | --- |
| `Assessing` | `assess` | `Assessing → ReadinessStage` | Branches to `Ready`, `AwaitingClarification`, or `Blocked` based on `verdict`, `mode`, and `round` count. `clearBlackboard = true`. |
| `AwaitingClarification` | `ask` | `AwaitingClarification → BpmnClarificationAnswers` | Pauses and waits for typed user answers via `WaitFor.formSubmission`. |
| `Ready` | `proceed` | `Ready → ReadyBpmnContext` | Feeds existing downstream chain. |
| `Blocked` | `terminate` | `Blocked → BpmnResult` | Terminates with `NEEDS_CLARIFICATION` status. |
| (outer class) | `reassess` | `(AwaitingClarification, BpmnClarificationAnswers) → Assessing` | Updates request with answers, reassesses, and returns `Assessing`. `clearBlackboard = true`. |

## `BpmnReadinessAgent` — scoped readiness sub-process

`BpmnReadinessAgent` exposes a single action, `assessReadiness` (`BpmnRequest → ProcessInputAssessment`), annotated `@AchievesGoal(name = "assessReadiness")`. It is **not** part of the orchestrator's plan. Instead, the orchestrator's `assessReadiness` action calls `BpmnReadinessInvoker`, whose implementation `AgentPlatformBpmnReadinessInvoker` creates a fresh agent process bound to **only** `BpmnReadinessAgent`:

```kotlin
val agent = agentPlatform.agents().find { it.name == "BpmnReadinessAgent" } ...
val process = agentPlatform.createAgentProcessFrom(agent, ProcessOptions(
    budget = Budget(actions = config.budget.readiness), ephemeral = true, ...
), request)
```

The single-agent binding is load-bearing: a whole-platform plan for `ProcessInputAssessment` would also match the orchestrator's own `assessReadiness` action (which calls this invoker), so an unscoped sub-process could re-select it and recurse without bound. Scoping the sub-process to the readiness agent removes that goal collision.

## `BpmnLayoutAgent` — standalone layout agent

`BpmnLayoutAgent` is a self-contained agent that owns the post-validation layout pipeline:

| Action | Input → Output | What happens |
| --- | --- | --- |
| `layoutBpmnXml` | `ValidatedBpmnXml → LayoutedBpmnXml` | Runs the embedded `bpmn-auto-layout` JS bundle (GraalJS, no LLM) via `BpmnLayoutPort` to assign diagram coordinates. |
| `validateFinalBpmnXml` | `LayoutedBpmnXml → FinalValidatedBpmnXml` | XSD-validates the layouted XML and checks BPMNDI completeness (one diagram/plane, shapes/edges for every node/sequence). Throws `BpmnLayoutCorruptionException` on failure. Carries `@AchievesGoal(name = "finalizeLayout")`. |

Although this agent exists and achieves its own goal, the orchestrator's `layout` action **does not use it** — the orchestrator runs the same `BpmnLayoutPort` + XSD check inline. `BpmnLayoutAgent` remains available as a standalone, separately-invokable layout goal.

## How actions chain

Actions never name each other directly. The planner threads outputs to inputs **by type**: `createOutline` returns `ValidatedOutline`, `composeGraph` takes `ValidatedOutline`, the planner connects them. Because the whole happy path now lives on one orchestrator, the chain is a single linear sequence of distinct types from `BpmnRequestDraft` through to `BpmnResult` — there is no blackboard name-matching (`@RequireNameMatch`) anywhere in the deployed plan.

## Adding a new pipeline step

To add a stage to the happy path:

1. Define the new domain types in `domain/` (or the owning module) for the step's input and output.
2. Add the real logic behind a public **port** in the owning module (a `@PrimaryAdapter @Component` if it is LLM-backed, a plain `@Component` otherwise).
3. Add a thin `@Action` to `BpmnGenerationAgent` that delegates to the port. The planner picks it up by type at startup; no explicit registration.
4. If the new step needs a separately-invokable goal, give its agent action an `@AchievesGoal(...)`.
5. Add a label for every new action name to `BpmnProgressProjectionObserver.ACTION_LABELS`.
6. Add a `*ModuleTest` to cover the new module's Spring wiring; `BpmnerModulithTest` verifies module boundaries.
