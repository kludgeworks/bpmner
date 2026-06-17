# GOAP Lifecycle & Repair Architecture

This document describes how bpmner's agent pipeline executes — how Embabel's planner chooses actions, how the framework surfaces failure modes — and the contract that Pkl rules use to declare their repair capability.

The happy path now runs on a **single orchestrating agent**, `BpmnGenerationAgent`. The validation stage executes an iterative repair loop inside the `BpmnRepairer` port using Embabel's `RepeatUntilAcceptable` mechanism (rather than a top-level GOAP loop in the plan). The GOAP framework concepts below still apply — they govern how the orchestrator's thirteen actions are planned and how the two support agents (`BpmnReadinessAgent`, `BpmnLayoutAgent`) run.

## What is GOAP, in this codebase

bpmner uses [Embabel](https://github.com/embabel/embabel-agent)'s **Goal-Oriented Action Planning** (GOAP) planner. Each `@Agent` class declares one or more `@Action` methods; each action declares its inputs as method parameters (the planner sees them as preconditions on the blackboard) and its output as the return type. A separate `@AchievesGoal` annotation marks the actions that satisfy a top-level goal — for bpmner the headline goal is `generateBpmn` (producing a `BpmnResult`).

The planner uses **cost-based A\*** over the action graph: at each tick it picks the cheapest applicable action whose inputs are currently available on the **blackboard** (Embabel's named in-memory store). Actions may declare additional **conditions** via `@Condition` methods or `post`/`pre` strings; those let the planner reason about state without smuggling booleans through return types.

Three signals shape execution:

- **`ReplanRequestedException`** — an action can request that the planner replan. In the repair loop, the no-progress and stuck fingerprint guards throw this exception, which is caught at the `BpmnRepairLoop` boundary and converted into a non-improving result to avoid aborting the sub-process while still halting progress on loops (see [The repair machinery](#the-repair-machinery)).
- **`ProcessExecutionStuckException`** — no applicable action exists for the goal. Thrown when the planner can't find a viable path forward.
- **`ProcessExecutionTerminatedException`** — `Budget(actions = N)` exhausted before a goal was reached.

Both of the latter are surfaced from `AgentProcessExecution.fromProcessStatus()`, the entry-point that produces typed exceptions on non-COMPLETED process states.

## Agent inventory

There are exactly **three** deployed `@Agent` classes. Only the orchestrator participates in the `generateBpmn` plan; the other two are invoked in their own scoped processes.

| Agent | File | Actions | Achieves goal | Role in the run |
| --- | --- | --- | --- | --- |
| `BpmnGenerationAgent` | `orchestration/internal/adapter/inbound/` | 13: `draft`, `resolve`, `assessReadiness`, `startAssessing`, `extractContract`, `createOutline`, `composeGraph`, `render`, `validate`, `layout`, `align`, `finish`, `reassess` | `generateBpmn` (on `finish`) | The orchestrator. Plans the whole happy path via a `@State` machine for readiness/clarification. |
| `BpmnReadinessAgent` | `readiness/internal/adapter/inbound/` | 1: `assessReadiness` | `assessReadiness` | Run as a **scoped sub-process** by the orchestrator's `assessReadiness` action. |
| `BpmnLayoutAgent` | `layout/internal/adapter/inbound/` | 2: `layoutBpmnXml`, `validateFinalBpmnXml` | `finalizeLayout` | Standalone layout agent. **Not** used by the orchestrator (layout runs inline). |

See [`agents.md`](./agents.md) for the per-action port delegation table.

## End-to-end flow

```text
   ┌───────────────────────────────────────────────────────────────────────┐
   │ Starting input                                                        │
   │   Shell: UserInput   |   Web: BpmnRequest (async, INTERACTIVE mode)   │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │ BpmnGenerationAgent  (single GOAP plan, 13 actions)                  │
   │                                                                       │
   │   draft                → BpmnRequestDraft                             │
   │   resolve              → BpmnRequest                                  │
   │   assessReadiness      → ProcessInputAssessment   (scoped sub-process)│
   │   startAssessing       → Assessing   (@State machine entry)           │
   │   extractContract      → ValidatedProcessContract                     │
   │   createOutline        → ValidatedOutline                            │
   │   composeGraph         → LaidOutProcessGraph                         │
   │   render               → RenderedBpmn                                │
   │   validate             repair loop → ValidatedBpmnXml                 │
   │   layout               inline → FinalValidatedBpmnXml                 │
   │   align                → BpmnAlignmentReport                         │
   │   finish  @AchievesGoal(generateBpmn) → BpmnResult                   │
   └───────────────────────────────────────────────────────────────────────┘
```

The orchestrator's `assessReadiness` action does not chain `BpmnReadinessAgent` into this plan. It calls `BpmnReadinessInvoker.assess(...)`, whose implementation `AgentPlatformBpmnReadinessInvoker` spins up a **separate** agent process bound to only `BpmnReadinessAgent` — see [Scoped readiness sub-process](#scoped-readiness-sub-process).

## Action chaining by type matching

Actions never name each other directly. The planner threads outputs to inputs **by type**: if some action returns `ValidatedOutline` and a later action takes `ValidatedOutline` as a parameter, the planner connects them. Because the whole happy path lives on one agent, the chain is a single linear sequence of distinct types — there is no blackboard name-matching (`@RequireNameMatch`) in the deployed plan.

Four of the orchestrator's actions are marked `actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE` — `layout`, `align`, `finish`, and `reassess` — so a failure there is not retried by the planner.

## Scoped readiness sub-process

The readiness assessment runs in its own ephemeral process:

```kotlin
val agent = agentPlatform.agents().find { it.name == "BpmnReadinessAgent" } ...
val process = agentPlatform.createAgentProcessFrom(agent, ProcessOptions(
    budget = Budget(actions = config.budget.readiness),
    ephemeral = true,
    listeners = listeners,
), request)
process.run()
return ProcessInputAssessment::class.java.cast(
    AgentProcessExecution.fromProcessStatus(request, process).output,
)
```

Binding the sub-process to **only** `BpmnReadinessAgent` is load-bearing. A whole-platform plan for `ProcessInputAssessment` would also match the orchestrator's own `assessReadiness` action (which calls this very invoker), so an unscoped sub-process could re-select it and recurse without bound. Scoping to the readiness agent removes that goal collision. The sub-process uses its own tighter `Budget(actions = bpmner.budget.readiness)` (default `20`).

## The `validate` action — repair loop

The orchestrator's `validate` action delegates to the `BpmnRepairer` port (`DefaultBpmnRepairer`), which executes an iterative repair loop via `BpmnRepairLoop` if the initial evaluation is not diagnostics-clean:

1. **Pre-flight scan** for unrecognized parser fallbacks; any are surfaced as `UNFIXABLE` diagnostics before Jackson serialisation.
2. **Normalise default flows** (`DefaultFlowAssigner`).
3. **Evaluate** via `BpmnContractAwareValidator`, which composes the `BpmnValidator` pipeline (structural + XSD + lint) with the `BpmnContractFidelityChecker` (contract→BPMN topology, only when the base evaluation has no blocking diagnostics).
4. **Iterative Repair** via `RepeatUntilAcceptable`: runs cost-aware repair appliers (local fix, LLM label patch, LLM structural patch, or full LLM rewrite) for up to `maxRepairIterations` attempts or until no blocking diagnostics remain.

`validateInitial` then returns a `ValidatedBpmnXml(definition, xml, diagnostics, repairAttempts)` and publishes either `BpmnValidationPassedEvent` (clean) or `BpmnValidationFailedEvent` (diagnostics remain).

## The repair machinery

The `repair/` module contains the iterative-repair infrastructure that is wired into the `RepeatUntilAcceptable` sub-process:

- `BpmnRepairAdvancer.revalidateAndAdvance(...)` — re-stamps default flows, fingerprint-guards against no-progress / repeated-output / stuck-blocking states (throwing `ReplanRequestedException` via `RepairReplans.signal(...)`), re-renders, re-validates, and appends to attempt history.
- `BpmnLocalFixApplier` / `BpmnLlmRepairApplier` — deterministic and LLM-driven repair appliers (`applyLlmLabelPatch` / `applyLlmStructuralPatch` / `applyFullLlmRewrite`).
- `BpmnRepairEvaluation`'s eligibility predicates (`hasLocalFixable`, `hasLlmEligible`, `hasLlmLabelEligible`, `hasLlmStructuralEligible`) that the loop uses to select the appropriate repair tier.

## Stuck vs Terminated

```text
ProcessExecutionStuckException        ProcessExecutionTerminatedException
─────────────────────────────         ─────────────────────────────────
The planner cannot find any           Budget exhausted (Budget.actions = N)
applicable action for the goal.       before reaching the goal. The planner
                                      could have picked an action; the
                                      ceiling stopped it.
```

Both are produced by `AgentProcessExecution.fromProcessStatus(request, process)`. `AgentPlatformBpmnAgentInvoker.generate()` (`generation/AgentPlatformBpmnAgentInvoker.kt`) uses this entry point specifically to surface the typed exceptions. The alternative `process.resultOfType()` path (used inside `AgentPlatformTypedOps.transform()`) would collapse non-COMPLETED states into a generic `IllegalArgumentException`, which is why the invoker stays on `fromProcessStatus`. The scoped readiness invoker (`AgentPlatformBpmnReadinessInvoker`) reads its sub-process output the same way.

## The repair contract

The **diagnostic classification** the repair loop relies on is sourced from each rule's `RepairMetadata` via `BpmnDiagnosticNormalizer`:

Every rule declares `RepairMetadata` in its Kotlin bean config (`src/main/kotlin/dev/groknull/bpmner/rules/internal/domain/beans/*RuleConfig.kt`).

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `kind` | `RepairKind` | `RepairKind.LLM_MODEL_PATCH` | How the diagnostic may be repaired |
| `safety` | `RepairSafety` | `RepairSafety.LLM_ONLY` | Whether the repair requires human review |
| `handler` | `String?` | `null` | For `LOCAL_MODEL_FIX`, the Spring bean name of the handler |
| `replacementMap` | `Map<String, String>?` | `null` | Optional map of source→replacement text |

This metadata round-trips into Kotlin via `RuleMetadata.repair` (see `api/RuleMetadata.kt`) and is read by `BpmnDiagnosticNormalizer` when stamping each diagnostic.

### `RepairKind`

The Kotlin `enum class RepairKind` (`api/RepairKind.kt`) defines the repair strategies:

| Value | Meaning |
| --- | --- |
| `LOCAL_MODEL_FIX` | A registered Kotlin handler edits the parsed `BpmnDefinition`. No LLM call. |
| `LLM_MODEL_PATCH` | Repair would come from an LLM-produced patch. Default for any rule without an explicit `kind`. |
| `LLM_XML_REWRITE` | Repair would come from a full LLM rewrite of the BPMN model. |
| `UNFIXABLE` | No automated fix; the diagnostic surfaces to the user. |

> The `LOCAL_XML_FIX` enum value is `@Deprecated` (collapsed into `LOCAL_MODEL_FIX`). Treat it as a synonym.

A separate `RepairDisposition` enum (`api/RepairDisposition.kt`, values `APPLIED` / `LLM_RESOLVED` / `UNRESOLVED` / `NOT_APPLICABLE`) classifies the *outcome* of a repair attempt; it was introduced during the migration and coexists with `RepairKind`.

### `RepairSafety`

| Value | Meaning |
| --- | --- |
| `SAFE_AUTOMATIC` | The repair may run without operator review. |
| `SAFE_MANUAL` | The repair is correct but should surface for confirmation. |
| `LLM_ONLY` | The repair is generated by the language model; never apply unattended. |

Independent of `kind`. `SAFE_AUTOMATIC` means the repair may run without operator review; `SAFE_MANUAL` means a human should confirm; `LLM_ONLY` is the default for LLM-routed rules. The field is consumed by observability and the operator-facing diagnostic stream.

### Handler registration (Kotlin side)

`LOCAL_MODEL_FIX` rules name a Kotlin handler in their Pkl `handler` field. At startup, `BpmnLocalModelFixHandlerRegistry` (`repair/internal/domain/`) collects every `BpmnLocalModelFixHandler` bean and indexes it by its `handlerName` (rejecting duplicates). A repair applier looks the handler up by name at dispatch time.

A startup guard, `BpmnLocalRepairCapabilityValidator` (`repair/internal/domain/`), listens on `ContextRefreshedEvent` and fails the context refresh if any `LOCAL_MODEL_FIX` capability names a handler that isn't registered. `AgentDeploymentValidator` (`config/`) performs an analogous check on deployed agents at startup.

## Why the GOAP shape?

Even with a single orchestrator and a single-pass validate, GOAP earns its keep:

- **Type-driven composition.** Adding a stage is adding a typed `@Action` and a port; the planner threads it by input/output type with no orchestrator rewiring.
- **Observable, typed failure modes.** STUCK and TERMINATED are distinct and surface through `fromProcessStatus()`; a bespoke exception would conflate "no path" with "out of budget."
- **Per-action retry policy.** Terminal actions declare `ActionRetryPolicy.FIRE_ONCE` so a model failure on, say, alignment isn't retried.
- **Scoped sub-processes.** Readiness runs as its own bounded process with its own budget, isolated from the main plan's goal set.

## Cross-references

- Agent file paths, the 13-action port table, and `@AchievesGoal` exports: see [`agents.md`](./agents.md).
- Pipeline overview, module map, and the inline-layout / validate-only stages: see [`pipeline-architecture.md`](./pipeline-architecture.md).
- Operator-facing tuning (budgets, profiles, severity overrides, troubleshooting): see [`operator-guide.md`](./operator-guide.md).
- Module structure, ports, and jMolecules roles: see [`hexagonal-architecture.md`](./hexagonal-architecture.md).
- Writing a new rule (Tier 1 Kotlin, Tier 2 Kotlin bean): see [`../linter/docs/rule-authoring-guide.md`](../linter/docs/rule-authoring-guide.md).
