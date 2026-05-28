# GOAP Lifecycle & Repair Architecture

This document describes how bpmner's agent pipeline executes — how Embabel's planner chooses actions, how diagnostics flow through the repair loop, how the framework surfaces failure modes — and the contract that Pkl rules use to declare their repair capability.

## What is GOAP, in this codebase

bpmner uses [Embabel](https://github.com/embabel/embabel-agent)'s **Goal-Oriented Action Planning** (GOAP) planner. Each `@Agent` class declares one or more `@Action` methods; each action declares its inputs as method parameters (the planner sees them as preconditions on the blackboard) and its output as the return type. A separate `@AchievesGoal` annotation marks the actions that satisfy a top-level goal — for bpmner that's `generateBpmn` (producing a `BpmnResult`).

The planner uses **cost-based A\*** over the action graph: at each tick it picks the cheapest applicable action whose inputs are currently available on the **blackboard** (Embabel's named in-memory store). Actions may declare additional **conditions** via `@Condition` methods; those let the planner reason about diagnostic state without smuggling booleans through return types.

Three signals shape execution:

- **`ReplanRequestedException`** — an action can request that the planner replan from the current blackboard, skipping this action for the next cycle (Embabel's "blacklist for replan" rule). Used by the repair agent's no-progress guards.
- **`ProcessExecutionStuckException`** — no applicable action exists for the goal. Thrown when the planner can't find a viable path forward (e.g. every remaining diagnostic is `UNFIXABLE`).
- **`ProcessExecutionTerminatedException`** — `Budget(actions = N)` exhausted before a goal was reached. Both are surfaced from `AgentProcessExecution.fromProcessStatus()`, the entry-point that produces typed exceptions on non-COMPLETED process states.

## Agent inventory

The pipeline that runs end-to-end for a `BpmnRequest → BpmnResult` traversal spans seven agents and 17 `@Action` methods total:

| Agent | File | Actions | Achieves goal | Configures |
|---|---|---|---|---|
| `BpmnReadinessAgent` | `readiness/internal/adapter/inbound/` | 1: `assessReadiness` | `assessReadiness` | `bpmner.readiness.*`, `bpmner.budget.readiness` |
| `BpmnContractAgent` | `contract/internal/adapter/inbound/` | 1: `extractProcessContract` | `extractProcessContract` | `bpmner.contract.*`, role: `contract-extractor` |
| `BpmnGeneratorAgent` | `generation/internal/adapter/inbound/` | 4: `createOutline`, `composeGraph`, `renderBpmnXml`, `finalizeBpmn` | `generateBpmn` (on `finalizeBpmn`) | role: `generator`, `bpmner.budget.generation` |
| `BpmnRepairAgent` | `repair/internal/adapter/inbound/` | 6: `validate`, `applyDeterministicFixes`, `applyLlmLabelPatch`, `applyLlmStructuralPatch`, `applyFullLlmRewrite`, `finalize` | (chained inside generator path) | roles: `repair-label`, `repair-patch`, `repair-rewrite` |
| `BpmnLayoutAgent` | `layout/...` | 3: `autoFixBpmnXml`, `layoutBpmnXml`, `validateFinalBpmnXml` | (chained) | GraalJS-backed; no LLM |
| `BpmnAlignmentAgent` | `alignment/internal/adapter/inbound/` | 1: `checkAlignment` | `checkAlignment` | `bpmner.alignment.*`, role: `alignment-validator` |
| `LlmRuleAgent` | `rules/internal/adapter/inbound/` | 1: `evaluateLlmRules` | `lintLlmRules` | `bpmner.lintBatchSize`, role: `linter` |

## End-to-end flow

```
   ┌───────────────────────────────────────────────────────────────────────┐
   │ Input                                                                 │
   │   BpmnRequest (process description, optional output file, style)      │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │ BpmnReadinessAgent.assessReadiness                                    │
   │   LLM: is the input rich enough to model?                             │
   │   → ProcessInputAssessment (READY | NEEDS_CLARIFICATION | BLOCKED)    │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 │  READY
                                 ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │ BpmnContractAgent.extractProcessContract                              │
   │   LLM + validator: distil the input into a typed ProcessContract     │
   │   → ValidatedProcessContract (activities, end states, flow rules)     │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │ BpmnGeneratorAgent  (4 actions)                                      │
   │                                                                       │
   │   createOutline      LLM + DefaultFlowAssigner + fidelity check       │
   │       ↓ ValidatedOutline                                              │
   │   composeGraph       deterministic — ownership, layout placeholders   │
   │       ↓ LaidOutProcessGraph                                           │
   │   renderBpmnXml      BPMN 2.0 XML rendering                           │
   │       ↓ RenderedBpmn                                                  │
   │   finalizeBpmn       writes result file; @AchievesGoal(generateBpmn)  │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 │  RenderedBpmn enters the repair loop
                                 ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │ BpmnRepairAgent  (GOAP loop — 6 actions, 6 conditions)                │
   │   validate                  cost 0   (always picked first)            │
   │   applyDeterministicFixes   cost 0.1                                  │
   │   applyLlmLabelPatch        cost 0.5                                  │
   │   applyLlmStructuralPatch   cost 0.7                                  │
   │   applyFullLlmRewrite       cost 0.9                                  │
   │   finalize                  pre: diagnosticsResolved                  │
   │       ↓ ValidatedBpmnXml                                              │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │ BpmnLayoutAgent  (3 actions)                                         │
   │   autoFixBpmnXml            GraalJS bounded XML cleanup               │
   │   layoutBpmnXml             bpmn-auto-layout via GraalJS              │
   │   validateFinalBpmnXml      XSD + lint final check                    │
   │       ↓ FinalValidatedBpmnXml                                         │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 │
                                 ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │ BpmnAlignmentAgent.checkAlignment                                     │
   │   LLM: does the generated BPMN match the ProcessContract?             │
   │   → AlignedBpmnXml (ALIGNED | PARTIALLY_ALIGNED | FAILED)             │
   │   FAILED throws BpmnAlignmentException                                │
   └─────────────────────────────┬─────────────────────────────────────────┘
                                 │  ALIGNED
                                 ▼
                ┌───────────────────────────────────┐
                │ BpmnGeneratorAgent.finalizeBpmn   │
                │   Writes the .bpmn file           │
                │   → BpmnResult(GENERATED, xml)    │
                └───────────────────────────────────┘
```

## Action chaining by type matching

Actions never name each other directly. The planner threads outputs to inputs by **type**: if some action returns `ValidatedOutline` and a later action takes `ValidatedOutline` as a parameter, the planner connects them. This is how cross-agent flow works without any explicit wiring: `BpmnGeneratorAgent.createOutline` returns `ValidatedOutline`; `BpmnGeneratorAgent.composeGraph` takes `ValidatedOutline`; the planner picks it up.

For most action chains a single instance of the type on the blackboard is enough. The exception is the repair loop — covered next — which threads its own evaluation type through six actions, all returning the same type. That's where `@RequireNameMatch` comes in.

## The repair loop in detail

The repair agent is a GOAP loop in miniature. Six actions all consume and produce `BpmnRepairEvaluation`; without `outputBinding` + `@RequireNameMatch`, the planner would see six interchangeable actions and lose track of which one's output should feed the next iteration.

### Blackboard threading

```kotlin
@Action(outputBinding = "repairEval", ...)
fun applyLlmLabelPatch(
    @RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation,
    context: ActionContext,
): BpmnRepairEvaluation { ... }
```

- **`outputBinding = "repairEval"`** publishes the return value to the named slot `repairEval` on the blackboard.
- **`@RequireNameMatch("repairEval")`** on every input parameter says "give me the value bound at `repairEval`, not just any `BpmnRepairEvaluation`."

That pair is how the planner threads a single evolving evaluation through the repair loop instead of accumulating sibling instances.

### Cost ordering

The four repair actions are sorted by cost; the planner always picks the cheapest applicable action:

| Action | Cost | Eligibility | What it does |
|---|---|---|---|
| `validate` | 0 (initial) | Always | Seeds the first `BpmnRepairEvaluation`, normalises default flows, runs the validator. |
| `applyDeterministicFixes` | 0.1 | `hasLocalFixable` (any `LOCAL_MODEL_FIX` diagnostic) | Dispatches each diagnostic to its declared Kotlin handler. No LLM call. |
| `applyLlmLabelPatch` | 0.5 | `hasLlmLabelEligible` (any LABEL-scope diagnostic) | LLM produces a `BpmnRepairPatch` against label-only changes. |
| `applyLlmStructuralPatch` | 0.7 | `hasLlmStructuralEligible` (OUTLINE/PHASE-scope) | LLM produces a structural patch (adds/removes elements). |
| `applyFullLlmRewrite` | 0.9 | `hasLlmEligible` (any `kind != UNFIXABLE`) | Last-resort: LLM rewrites the entire `BpmnDefinition`. |
| `finalize` | (terminal) | `diagnosticsResolved` precondition | Emits `ValidatedBpmnXml`; ends the loop. |

The repair agent's six `@Condition` methods (`hasDiagnostics`, `diagnosticsResolved`, `hasLocalFixable`, `hasLlmEligible`, `hasLlmLabelEligible`, `hasLlmStructuralEligible`) are the planner's view into the current `BpmnRepairEvaluation` — they let it pick the cheapest applicable action without reading the blackboard contents directly.

### `ReplanRequestedException` semantics

The repair agent's three fingerprint guards (no-progress, repeated-fingerprint, stuck-blocking) and its two LLM-format error catches throw `ReplanRequestedException` via `RepairReplans.signal(...)`. The planner's response (verified from `embabel-agent`'s `SimpleAgentProcess.kt:163-180`):

1. Apply any blackboard updates carried by the exception.
2. **Blacklist this action for the next planning cycle**. The planner cannot pick the same action twice in a row.
3. Emit a `ReplanRequestedEvent` for observers.
4. Keep status `RUNNING`. The next tick re-plans from scratch.

**Important: `ReplanRequestedException` does NOT consume an action against `Budget(actions = N)`**. The action's invocation is not added to the process history (the exception is caught before the success path completes). `MaxActionsEarlyTerminationPolicy` only counts entries in history, so replans flow free.

This is intentional — replans are a control-flow signal, not work — but it means **budget exhaustion happens via repeated successful actions, not via replans**. A pathological case (the kind the TERMINATED integration test exercises) is when an LLM action keeps returning a *different* result each time (no fingerprint repeat, no replan), but the result is never good enough to satisfy `diagnosticsResolved`. The action runs to success each iteration, history grows by one per attempt, and `Budget(actions = 100)` terminates the loop after ~100 attempts.

### STUCK vs TERMINATED

```
ProcessExecutionStuckException        ProcessExecutionTerminatedException
─────────────────────────────         ─────────────────────────────────
The planner cannot find any           Budget exhausted (Budget.actions = N)
applicable action for the goal.       before reaching the goal. The planner
                                      could have picked an action; the
                                      ceiling stopped it.

Common cause:                         Common cause:
  every remaining diagnostic            an LLM action keeps producing
  is UNFIXABLE — no repair              outputs that fail validation,
  action is eligible.                   each one counted as one action.
```

Both are produced by `AgentProcessExecution.fromProcessStatus(request, process)`. `AgentPlatformBpmnAgentInvoker.generate()` (`generation/AgentPlatformBpmnAgentInvoker.kt`) uses this entry point specifically to surface the typed exceptions. The alternative `process.resultOfType()` path (used inside `AgentPlatformTypedOps.transform()`) would collapse both into a generic `IllegalArgumentException`, which is why the invoker stays on `fromProcessStatus`.

## The Pkl-side repair contract

Every rule declares a `Repair` block in its Pkl module:

```pkl
class Repair {
  kind: RepairKind = "LLM_MODEL_PATCH"
  safety: RepairSafety = "LLM_ONLY"
  handler: String?(
    if (kind == "LOCAL_MODEL_FIX")
      this != null && !this.isEmpty
    else
      this == null,
  ) = null
  replacementMap: Mapping<String, String>? = null
}
```

This metadata round-trips into Kotlin via `RuleMetadata.repair` (see `api/RuleMetadata.kt`) and is read by `BpmnDiagnosticNormalizer` when stamping each diagnostic with its repair classification. The repair agent then dispatches based on `RepairKind`.

### `RepairKind`

```pkl
typealias RepairKind = "LOCAL_MODEL_FIX" | "LLM_MODEL_PATCH" | "LLM_XML_REWRITE" | "UNFIXABLE"
```

| Value | Meaning | Repair action that handles it |
|---|---|---|
| `LOCAL_MODEL_FIX` | A registered Kotlin handler edits the parsed `BpmnDefinition`. No LLM call. | `applyDeterministicFixes` |
| `LLM_MODEL_PATCH` | LLM produces a `BpmnRepairPatch`; `BpmnPatchApplier` applies the patch. Default for any rule without an explicit `kind`. | `applyLlmLabelPatch` (LABEL scope) or `applyLlmStructuralPatch` (OUTLINE/PHASE scope) |
| `LLM_XML_REWRITE` | LLM rewrites the entire BPMN model. Last-resort tier. | `applyFullLlmRewrite` |
| `UNFIXABLE` | No fix is offered. The diagnostic surfaces to the user; `hasLlmEligible` returns false for this kind, so no repair action becomes applicable. | (none — surfaces as STUCK) |

> The `LOCAL_XML_FIX` enum value also exists in `RepairKind.kt` but is `@Deprecated`. Treat it as a synonym for `LOCAL_MODEL_FIX` in new rules.

### `RepairSafety`

```pkl
typealias RepairSafety = "SAFE_AUTOMATIC" | "SAFE_MANUAL" | "LLM_ONLY"
```

Independent of `kind`. `SAFE_AUTOMATIC` means the repair can run without human review; `SAFE_MANUAL` means a human should confirm; `LLM_ONLY` is the default for LLM-routed rules. The repair agent does not currently gate on safety, but the field is consumed by observability and the operator-facing diagnostic stream.

### Handler registration (Kotlin side)

`LOCAL_MODEL_FIX` rules name a Kotlin handler in their Pkl `handler` field. At startup, `BpmnLocalModelFixHandlerRegistry` collects every `@Component LocalModelFixHandler` bean by name. `applyDeterministicFixes` looks up the handler by name at dispatch time. A missing handler with a `LOCAL_MODEL_FIX` rule fails fast at the dispatch site (the registry warns at startup; the handler resolves to `null` and the diagnostic falls through to `UNFIXABLE` behaviour).

## A worked iteration

Trace a single repair tick. The agent enters with a `RenderedBpmn` whose validation produced two diagnostics: one `LOCAL_MODEL_FIX` (handler: `stripTypeWords`) and one `LLM_XML_REWRITE`.

1. **`validate`** runs (cost 0). It normalises the definition, evaluates rules, and publishes the first `BpmnRepairEvaluation` to `repairEval`.
2. The planner sees `hasDiagnostics = true`. Three actions are applicable: `applyDeterministicFixes` (0.1), `applyFullLlmRewrite` (0.9). LABEL-scope and structural-scope actions are not eligible because no diagnostic has those scopes.
3. The planner picks `applyDeterministicFixes` (cheapest). The Kotlin handler runs, removes the type-words diagnostic. The agent's `revalidateAndAdvance` helper re-runs validation; the LOCAL diagnostic is resolved; the `LLM_XML_REWRITE` diagnostic remains.
4. The fingerprint guard runs. The definition has changed (one node renamed) so no replan signal is needed. The new `BpmnRepairEvaluation` returns; the planner ticks.
5. Now only `applyFullLlmRewrite` is applicable. The planner picks it. The action calls the LLM, gets a new `BpmnDefinition`, re-validates. If the new definition resolves the diagnostic, `diagnosticsResolved` becomes true.
6. `finalize` becomes applicable (its precondition was `diagnosticsResolved`). The planner picks it. It emits `ValidatedBpmnXml` and the repair loop exits.

If step 5 had failed (LLM returns yet another invalid definition), step 5 repeats. Each iteration is one action against `Budget(actions = 100)`. After 100 such iterations, `ProcessExecutionTerminatedException` surfaces from `fromProcessStatus()`.

## Why the GOAP shape?

A hand-rolled while-loop over a strategy chain could in principle do the same work. The planner gives us things that would otherwise be open-coded:

- **Cost-based escalation**: the planner naturally prefers cheap repairs (local Kotlin handlers) over expensive ones (full LLM rewrite). A while-loop would have to encode this as a strategy-chain order.
- **Observable failure modes**: STUCK and TERMINATED are typed and operationally distinct. A single bespoke exception would conflate them.
- **Per-action retry policy**: actions can declare `actionRetryPolicy = FIRE_ONCE` (the alignment agent does this — a model failure on alignment is not retried), or rely on the planner's cost ordering to pick a different action. A loop has no equivalent.
- **Cross-agent composition**: the repair agent doesn't know about layout or alignment, but the planner threads them by type. New post-repair stages can be added by declaring an action that consumes `ValidatedBpmnXml`; no orchestrator change.

## Cross-references

- Agent file paths and `@AchievesGoal` exports: see [`agents.md`](./agents.md).
- Pipeline overview without GOAP detail: see [`pipeline-architecture.md`](./pipeline-architecture.md).
- Operator-facing tuning (budgets, profiles, severity overrides, troubleshooting): see [`operator-guide.md`](./operator-guide.md).
- Writing a new rule (Tier 1 Kotlin, Tier 2 Pkl): see [`../linter/docs/rule-authoring-guide.md`](../linter/docs/rule-authoring-guide.md).
