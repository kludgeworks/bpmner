# Agent Overview

bpmner is composed of eight Embabel `@Agent` classes. Each one owns a bounded responsibility and exposes one or more `@Action` methods; the framework's GOAP planner chains them by type. This page is a quick lookup: what each agent is for, where it lives, and what configures it.

For the planner mechanics — how actions are chained, cost ordering, failure modes — see [`goap-lifecycle.md`](./goap-lifecycle.md).

| Agent | File | Actions | Achieves goal | Configures |
|---|---|---|---|---|
| `BpmnGenerationGateAgent` | `generation/internal/adapter/inbound/BpmnGenerationGateAgent.kt` | 6: `draftBpmnRequest`, `resolveBpmnRequest`, `approveReadyRequest`, `askForClarification`, `applyClarificationAnswers`, `readinessBlocked` | `prepareBpmnGeneration` | `bpmner.readiness.*`, role: `readiness-assessor` |
| `BpmnReadinessAgent` | `readiness/internal/adapter/inbound/BpmnReadinessAgent.kt` | 1: `assessReadiness` | `assessReadiness` | `bpmner.readiness.*`, `bpmner.budget.readiness`, role: `readiness-assessor` |
| `BpmnContractAgent` | `contract/internal/adapter/inbound/BpmnContractAgent.kt` | 1: `extractProcessContract` | `extractProcessContract` | `bpmner.contract.*`, role: `contract-extractor` |
| `BpmnGeneratorAgent` | `generation/internal/adapter/inbound/BpmnGeneratorAgent.kt` | 4: `createOutline`, `composeGraph`, `renderBpmnXml`, `finalizeBpmn` | `generateBpmn` (on `finalizeBpmn`) | role: `generator`, `bpmner.budget.generation`, `bpmner.logging.dump-artifacts` |
| `BpmnRepairAgent` | `repair/internal/adapter/inbound/BpmnRepairAgent.kt` | 6: `validate`, `applyDeterministicFixes`, `applyLlmLabelPatch`, `applyLlmStructuralPatch`, `applyFullLlmRewrite`, `finalize` | (chained into the generator path) | roles: `repair-label`, `repair-patch`, `repair-rewrite`; `bpmner.repair.*` |
| `BpmnAlignmentAgent` | `alignment/internal/adapter/inbound/BpmnAlignmentAgent.kt` | 1: `checkAlignment` | `checkAlignment` | `bpmner.alignment.*`, role: `alignment-validator` |
| `BpmnLayoutAgent` | `layout/internal/adapter/inbound/BpmnLayoutAgent.kt` | 3: `autoFixBpmnXml`, `layoutBpmnXml`, `validateFinalBpmnXml` | (chained) | GraalJS-backed; no LLM |
| `LlmRuleAgent` | `rules/internal/adapter/inbound/LlmRuleAgent.kt` | 1: `evaluateLlmRules` | `lintLlmRules` | `bpmner.lintBatchSize`, role: `linter` |

Twenty-five `@Action` methods total. The `BpmnProgressProjectionObserver` maps pipeline progress to user-facing labels — see [`operator-guide.md`](./operator-guide.md#progress-events-sse).

## How actions chain

Actions never name each other directly. The planner threads outputs to inputs by type — `BpmnGeneratorAgent.createOutline` returns `ValidatedOutline`, `BpmnGeneratorAgent.composeGraph` takes `ValidatedOutline`, the planner connects them. The same mechanism crosses agent boundaries: `BpmnGeneratorAgent.renderBpmnXml` returns `RenderedBpmn`; `BpmnRepairAgent.validate` takes `RenderedBpmn`; cross-agent chain established without any explicit wiring.

The repair agent is the one exception: its six actions all return the same type (`BpmnRepairEvaluation`) and the planner needs `outputBinding = "repairEval"` + `@RequireNameMatch("repairEval")` to thread one evolving instance through all six. See [`goap-lifecycle.md`](./goap-lifecycle.md#blackboard-threading).

## Adding a new agent

If you're adding a new pipeline stage as a new agent:

1. Define new domain types in `core/` (input and output of the new agent).
2. Create the `@Agent` class under `<module>/internal/adapter/inbound/`.
3. Annotate one or more `@Action` methods. The planner picks them up at startup; no explicit registration.
4. If the new agent should achieve a top-level goal, annotate one action with `@AchievesGoal(name = "...")`.
5. Add the agent to the table above and add labels to `BpmnProgressProjectionObserver.ACTION_LABELS` for every new action.
6. Add a `*ModuleTest` to cover the new module's Spring wiring.
