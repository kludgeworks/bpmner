# BPMN Generation Pipeline Architecture

`bpmner` turns a plain-language business-process description into validated BPMN 2.0 XML by chaining Embabel agents over a sequence of strongly-typed domain objects. Each stage narrows the failure mode: the LLM owns *what* the process means; deterministic Kotlin code owns *how* it is structured, laid out, and validated.

This document maps the end-to-end pipeline. The Embabel GOAP planner — how actions are chained, how the repair loop iterates, how failure modes surface as typed exceptions — has its own deeper dive in [`goap-lifecycle.md`](./goap-lifecycle.md). The Pkl repair contract (RepairKind, RepairSafety, handler registration) is documented there too.

## Module map

The codebase is a Spring Modulith application under `dev.groknull.bpmner.*`. Each module owns one bounded responsibility; cross-module dependencies are restricted to the public package of each module. The *structural* shape of each module — ports, adapters, domain services, and the jMolecules annotations that mark each role — is documented separately in [`hexagonal-architecture.md`](./hexagonal-architecture.md).

| Module | Owns | Key public types |
| --- | --- | --- |
| `core/` | Shared domain model, configuration, fingerprints, naming policy. No Spring visibility restrictions. | `BpmnRequest`, `BpmnDefinition`, `BpmnConfig`, `BpmnDiagnostic`, `RepairKind`, `LaidOutProcessGraph`, `RenderedBpmn`, `ValidatedBpmnXml`, `FinalValidatedBpmnXml`, `BpmnResult`. |
| `readiness/` | Guardrail 1: Heuristic + LLM input assessment, clarification discovery, readiness report generation, ready-state handoff. | `BpmnReadinessAgent`, `BpmnReadinessInvoker`, `ProcessInputAssessment`, `ReadinessVerdict`, `ReadyBpmnContext`. |
| `contract/` | Guardrail 2: Extraction of source-grounded process contracts, multi-source evidence tracking. | `BpmnContractAgent`, `ProcessContract`, `ValidatedProcessContract`. |
| `generation/` | Embabel-native request intake, readiness gating, LLM-driven typed generation, structural composition, ownership assignment, XML rendering, file writing. | `BpmnGenerationGateAgent`, `BpmnGeneratorAgent`, `BpmnAgentInvoker`, `BpmnRenderer` (port), `BpmnResult`, `BpmnGeneratedEvent`. |
| `validation/` | Diagnostic discovery: BPMN definition checks, XSD validation, in-process rule-engine evaluation, capability stamping. | `BpmnValidator` (port), `BpmnLintingPort`, `BpmnXsdValidationPort`, `BpmnRuleGuidancePort`, `BpmnValidationFailedEvent`, `BpmnValidationPassedEvent`. |
| `repair/` | Local-first deterministic repair, LLM patch / rewrite via Embabel GOAP planner. Six actions chained via `outputBinding = "repairEval"`; cost ordering picks the cheapest applicable tier each iteration. See [`goap-lifecycle.md`](./goap-lifecycle.md). | `BpmnRepairAgent`, `BpmnRepairEvaluation`, `RepairKind`. |
| `layout/` | Bounded pre-layout XML cleanup, deterministic auto-layout, final post-layout validation. | `BpmnLayoutAgent`, `BpmnLayoutPort`. |
| `alignment/` | Guardrail 3: Semantic comparison of generated BPMN vs process contract, invented-task detection. | `BpmnAlignmentAgent`, `BpmnAlignmentReport`, `AlignmentVerdict`. |
| `observability/` | Process-finished summary, validation event logging, per-attempt observers. | `BpmnerRunSummaryListener`, `BpmnPipelineObserver`. |
| `config/` | OpenAI-compatible provider model configuration (e.g. OpenRouter). | `OpenRouterModelsConfig`. |

Module boundaries are verified by `BpmnerModulithTest`; the `internal` adapter packages under each module are not importable from outside.

## End-to-end pipeline

```text
           ┌──────────────────────┐             ┌──────────────────────┐
           │ Shell UserInput via  │             │ Web/programmatic     │
           │ Embabel x / execute  │             │ BpmnRequest +        │
           └──────────┬───────────┘             │ ProcessInputAssessment│
                      ▼                         └──────────┬───────────┘
            ┌─────────────────────────────────────────────────────────┐
            │        BpmnGenerationGateAgent  (generation/)           │
            │                                                         │
            │  draftBpmnRequest ── LLM ──► BpmnRequestDraft           │
            │  resolveBpmnRequest ───────► BpmnRequest                │
            │  assessReadiness ──────► ProcessInputAssessment         │
            │              │                                          │
            │              ▼                                          │
            │       READY (or seeded) ─────────► ReadyBpmnContext     │
            │       NEEDS_CLARIFICATION + interactive ► WaitFor form   │
            │       single-shot / round limit ─► BpmnResult           │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
            ┌─────────────────────────────────────────────────────────┐
            │           BpmnContractAgent  (contract/)                │
            │                                                         │
            │  extractProcessContract ── LLM ──► ProcessContract      │
            │              │                                          │
            │              ▼                                          │
            │       validateContract ──► ValidatedProcessContract     │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
            ┌─────────────────────────────────────────────────────────┐
            │           BpmnGeneratorAgent  (generation/)             │
            │           4 actions                                     │
            │                                                         │
            │  createOutline   LLM + DefaultFlowAssigner + fidelity   │
            │              │   check (inline)                         │
            │              ▼                                          │
            │       ValidatedOutline ─► composeGraph                  │
            │              │           deterministic — compose,       │
            │              │           ownership, layout (inline)     │
            │              ▼                                          │
            │       LaidOutProcessGraph ─► renderBpmnXml              │
            │              │                                          │
            │              ▼                                          │
            │       RenderedBpmn ────────────► BpmnGeneratedEvent     │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
            ┌─────────────────────────────────────────────────────────┐
            │            BpmnRepairAgent  (repair/)                   │
            │            GOAP loop — 6 actions                        │
            │                                                         │
            │  validate                  cost 0   (always first)      │
            │  applyDeterministicFixes   cost 0.1                     │
            │  applyLlmLabelPatch        cost 0.5                     │
            │  applyLlmStructuralPatch   cost 0.7                     │
            │  applyFullLlmRewrite       cost 0.9                     │
            │  finalize                  pre: diagnosticsResolved     │
            │                                                         │
            │  Embabel planner picks cheapest applicable action       │
            │  each iteration. `BpmnRepairEvaluation` threads through │
            │  via outputBinding = "repairEval" + @RequireNameMatch.  │
            │  See [`goap-lifecycle.md`](./goap-lifecycle.md) for     │
            │  cost ordering, replan semantics, STUCK vs TERMINATED.  │
            │                                                         │
            │                ──► ValidatedBpmnXml                     │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
            ┌─────────────────────────────────────────────────────────┐
            │           BpmnAlignmentAgent  (alignment/)              │
            │                                                         │
            │  checkAlignment ── LLM ──► BpmnAlignmentReport          │
            │              │                                          │
            │              ▼                                          │
            │       if FAILED ──► block & throw                       │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
            ┌─────────────────────────────────────────────────────────┐
            │            BpmnLayoutAgent  (layout/)                   │
            │                                                         │
            │  ValidatedBpmnXml ─► layoutBpmnXml                      │
            │     │ deterministic layout via embedded bpmn-auto-layout│
            │     ▼                                                   │
            │  LayoutedBpmnXml ─► validateFinalBpmnXml                │
            │     │ XSD validation only                               │
            │     │ throws BpmnLayoutCorruptionException on failure   │
            │     ▼                                                   │
            │  FinalValidatedBpmnXml                                  │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
                  BpmnGeneratorAgent.writeBpmn
                           │
                           ▼
                       BpmnResult
                   (status + xml + path)
```

The arrows between domain types are exact: each agent action declares its input and output type via Embabel `@Action`, and the agent platform resolves the chain by type. Inserting a new step is a matter of defining a new type and an action that produces it.

## Stage 1 — Generation (`generation/`)

`BpmnGeneratorAgent` deliberately splits "ask the LLM" from "structure the result." Only `createOutline` calls the model; everything after it is deterministic code operating on the typed `BpmnDefinition` the model returned. The four-action shape inlines the ownership and layout derivations into `composeGraph` so intermediate types don't cross action boundaries unnecessarily.

| Action | Input → Output | What happens |
| --- | --- | --- |
| `createOutline` | `(ReadyBpmnContext, ValidatedProcessContract) → ValidatedOutline` | Builds a generator prompt, calls `promptRunner.createObject(BpmnDefinition::class.java)`, applies `DefaultFlowAssigner` to stamp default outgoing flows on exclusive gateways, runs the inline fidelity check (`processId`/`processName` non-blank, `BpmnContractFidelityChecker`). Emits a `ValidatedOutline` containing the `BpmnDefinition`, the request, computed `OutlineMetrics`, and any non-blocking diagnostics. |
| `composeGraph` | `ValidatedOutline → LaidOutProcessGraph` | Single deterministic action that absorbed five Phase-pre-5 actions. Builds the `objectOwnersByObjectRef` map (every node and sequence stamped with the main phase), derives the `elementOwnersByElementId` map (mirrors object ownership plus `_di` diagram-element ids), and wraps the whole thing in `LaidOutProcessGraph`. The intermediate types `ComposedProcessGraph` and `OwnedElementGraph` still exist internally (the repair agent reads `OwnedElementGraph` via `LaidOutProcessGraph.ownedGraph`) but they no longer cross an action boundary. |
| `renderBpmnXml` | `(ReadyBpmnContext, LaidOutProcessGraph) → RenderedBpmn` | `BpmnRenderer` (Camunda model API) builds semantic XML (no BPMNDI — layout coordinates come later) and a `BpmnElementIndex` mapping element ids to render objects. Emits `BpmnGeneratedEvent`. |
| `finalizeBpmn` | `(ReadyBpmnContext, AlignedBpmnXml) → BpmnResult` | UTF-8 writes the final XML to the requested output file if one is configured, returns the result. Carries `@AchievesGoal(name = "generateBpmn")` — this is the top-level goal the Embabel planner is trying to reach. |

### Why a typed `BpmnDefinition`, not raw XML

The LLM produces an object with explicit semantic fields (nodes, sequences). This makes deterministic repair possible: a model-level patch like `SET_NODE_NAME(id, name)` operates on the typed graph, not a regex on XML. XML rendering happens *after* all validation and repair; it's the last deterministic step, not the first thing the LLM sees. Diagram coordinates are deliberately absent — they are computed downstream by the auto-layout stage so the LLM can never produce a layout that fights the layout engine.

## Stage 2 — Repair (`repair/`)

`BpmnRepairAgent` is a six-action Embabel GOAP agent. The planner picks the cheapest applicable action each iteration; a `BpmnRepairEvaluation` blackboard threads through every action via `outputBinding = "repairEval"` + `@RequireNameMatch("repairEval")` so the loop accumulates state across iterations.

```text
RenderedBpmn ──► validate (cost 0) ──► BpmnRepairEvaluation ──► repairEval blackboard
                                                │
                                                ▼
                                      BpmnContractAwareValidator.evaluate
                                                │
                              ┌─────────────────┴─────────────────┐
                              │  BpmnDefinitionValidator           │ structural sanity
                              │  graph.validateOwnership           │ ownership integrity
                              │  BpmnXsdValidationPort             │ schema compliance
                              │  BpmnLintingPort.lint              │ rule-engine diagnostics
                              │  BpmnDiagnosticNormalizer          │ stamps RepairKind on each
                              └────────────────────────────────────┘
                                                │
                                                ▼
                              planner picks cheapest applicable action:
                                                │
              ┌─────────────────────────────────┴─────────────────────────────┐
              │  applyDeterministicFixes        (cost 0.1)                    │
              │   eligible when: hasLocalFixable (any LOCAL_MODEL_FIX)        │
              │   dispatches to registered Kotlin handler — no LLM call       │
              ├───────────────────────────────────────────────────────────────┤
              │  applyLlmLabelPatch             (cost 0.5)                    │
              │   eligible when: hasLlmLabelEligible (LABEL-scope diagnostic) │
              │   prompts LLM for a label-only BpmnRepairPatch                │
              ├───────────────────────────────────────────────────────────────┤
              │  applyLlmStructuralPatch        (cost 0.7)                    │
              │   eligible when: hasLlmStructuralEligible (OUTLINE/PHASE)     │
              │   prompts LLM for a structural BpmnRepairPatch                │
              ├───────────────────────────────────────────────────────────────┤
              │  applyFullLlmRewrite            (cost 0.9)                    │
              │   eligible when: hasLlmEligible (any kind != UNFIXABLE)       │
              │   prompts LLM for the whole BpmnDefinition                    │
              └───────────────────────────────────────────────────────────────┘
                                                │
                                                ▼  diagnosticsResolved
                                          finalize ──► ValidatedBpmnXml
```

Key invariants enforced by the planner + agent:

- **Cost-based local-first.** The planner always picks the cheapest applicable action. Deterministic local fixes (0.1) run before any LLM call (0.5–0.9). The LLM tiers escalate by repair scope: label patches before structural patches before full rewrites.
- **No re-asking the LLM about resolved issues.** The agent's `revalidateAndAdvance` helper re-validates after each repair action; resolved diagnostics drop off the evaluation and never reach the next prompt.
- **Fail loud on stuck states.** Three guards in `revalidateAndAdvance` throw `ReplanRequestedException` via `RepairReplans.signal(...)`: no-progress (definition fingerprint unchanged), repeated-fingerprint, and stuck-blocking (blocking diagnostic fingerprint repeats). The planner blacklists the action for the next cycle, forcing a different repair tier. See [`goap-lifecycle.md`](./goap-lifecycle.md) for the precise semantics (and the gotcha: `ReplanRequestedException` does NOT consume a budget action).
- **Typed failure modes.** Budget exhaustion surfaces as `ProcessExecutionTerminatedException` from `AgentProcessExecution.fromProcessStatus()`. No-applicable-action surfaces as `ProcessExecutionStuckException`. Both flow up through `AgentPlatformBpmnAgentInvoker.generate()`.

The Pkl repair contract — what each `RepairKind` means, how rules declare their `Repair` block, how handlers are registered — is documented in [`goap-lifecycle.md`](./goap-lifecycle.md#the-pkl-side-repair-contract).

## Stage 3 — Layout (`layout/`)

`BpmnLayoutAgent` runs after the repair loop has produced semantically valid XML. It is deliberately narrow: it neither re-runs the repair loop nor invokes the LLM.

| Action | Input → Output | What happens |
| --- | --- | --- |
| `layoutBpmnXml` | `ValidatedBpmnXml → LayoutedBpmnXml` | `BpmnLayoutPort` runs the embedded `bpmn-auto-layout` JS bundle in GraalJS to assign deterministic diagram coordinates (waypoints, shape bounds). |
| `validateFinalBpmnXml` | `LayoutedBpmnXml → FinalValidatedBpmnXml` | XSD-validates the layouted XML against the Camunda BPMN schema. Semantic lint rules already ran pre-layout and don't repeat here. XSD failure throws `BpmnLayoutCorruptionException` — the agent does **not** re-enter repair. |

Final validation is intentionally narrow: it catches structural corruption from the layout library itself. Semantic correctness was settled by the repair loop; if the auto-layout pass somehow breaks the XML schema, that's a layout-engine bug, not something the LLM should be asked to fix.

## Validation as a shared service

`validation/` is consumed by both the repair loop (via `BpmnValidator.evaluate`) and the layout agent (via `BpmnLintingPort.lint` and `BpmnXsdValidationPort.validateDetailed`). It owns:

- `BpmnDefinitionValidator` — structural invariants on the typed model.
- `BpmnXsdValidator` — strict BPMN 2.0 XSD compliance.
- `RuleEngineLintingAdapter` — implements `BpmnLintingPort` by delegating to the
  `rules` module's `RuleEngine` and projecting `RuleRegistry` metadata into
  `LintIssue` / `BpmnLintRuleCapability` shapes.
- `BpmnDiagnosticNormalizer` — looks up the Pkl-declared capability and stamps
  each diagnostic with `kind`, `repairSafety`, and `fixHandler`; infers
  `repairScope` from ownership context.
- `BpmnLocalRepairCapabilityValidator` — startup guard: any `LOCAL_MODEL_FIX`
  capability whose handler isn't registered fails the Spring context refresh.

The `validation` module emits `BpmnValidationPassedEvent` and `BpmnValidationFailedEvent`; `observability/` listeners turn those into log lines and per-run summaries.

## Configuration

`BpmnConfig` (`@ConfigurationProperties("bpmner")`) controls the pipeline at runtime:

For the full configuration reference (every `bpmner.*` YAML key, defaults, ranges, and when to tune) see [`operator-guide.md`](./operator-guide.md). The high-level surface:

| Property | Default | Effect |
| --- | --- | --- |
| `bpmner.budget.generation` | `100` | `Budget(actions = N)` ceiling for the generation goal. Generation + repair share this budget per process. |
| `bpmner.budget.readiness` | `20` | Tighter budget for the readiness agent (no repair loop). |
| `bpmner.rules.profile` | `recommended` | Named rule profile loaded at startup. `strict` bumps every WARNING-default rule to ERROR. |
| `bpmner.rules.severity-overrides` | `{}` | Per-rule severity escape hatch applied on top of the active profile. |
| `bpmner.generator` / `bpmner.repairer` / etc. | role-based personas | Each agent has a `Persona` + `LlmOptions.withLlmForRole(...)` slot. |
| `bpmner.logging.dump-artifacts` | `false` | When `true`, emits truncated previews of every intermediate artifact at DEBUG. |

Model role bindings (`generator`, `repair-label`, `repair-patch`, `repair-rewrite`, `readiness-assessor`, `contract-extractor`, `alignment-validator`, `linter`) are resolved by the active Spring profile (`anthropic`, `openai`, `gemini`, `mistral`, `deepseek`, or `llama`). See the top-level `README.md` for invocation.

## Observability surface

Three layers, increasing in granularity:

- **`BpmnerRunSummaryListener`** — one line per agent process completion: total time, action count, models used, total cost, prompt/completion tokens, plus a per-action timing breakdown.
- **`BpmnPipelineObserver`** — listens on `BpmnValidationFailedEvent` / `BpmnValidationPassedEvent`. Per-attempt summary of source-grouped diagnostic counts.
- **`BpmnRepairAgent`** — single structured INFO line per repair attempt (`Validation summary: graph=… xsd=… lint=… repairScope=… accepted=… repairs=…`). Per-action progress events flow through `BpmnProgressProjectionObserver` for SSE consumers.

All three use SLF4J at INFO with bracketed parameter placeholders, so they are queryable with grep or log aggregation without per-call string formatting.

## Where each acceptance check lives

For someone reading this doc to find the test that proves an invariant:

| Invariant | Verified by |
| --- | --- |
| Generator outputs a typed `BpmnDefinition`, not raw XML. | `BpmnGeneratorAgentTest`, `BpmnGenerationGateAgentTest`. |
| Phase composition preserves ownership. | `BpmnOwnershipTest`. |
| Validation pipeline normalises rule prefixes and stamps `kind`. | `BpmnDiagnosticNormalizerTest`. |
| Cost-based repair runs cheaper actions before LLM tiers. | `BpmnRepairAgentIntegrationTest` (chain wiring), `RepairRoutingModuleTest`. |
| LLM prompts only contain unresolved diagnostics. | `BpmnRepairPromptFactoryTest`, `RepairRoutingModuleTest`. |
| Startup fails when a declared-local rule has no handler. | `BpmnLocalRepairCapabilityValidatorTest`, `RepairStartupValidationTest`. |
| Layout agent does not re-enter the repair loop. | `BpmnLayoutAgentTest`. |
| Final-validation failures throw rather than retry. | `BpmnLayoutAgentTest`, `BpmnAgentFlowSystemTest`. |
| End-to-end flow writes the requested file. | `BpmnAgentFlowSystemTest`. |
| GOAP failure modes surface as typed exceptions. | `BpmnRepairAgentIntegrationTest` (STUCK, TERMINATED), `BpmnAlignmentFailureIntegrationTest`. |

## Adding a new pipeline stage

If you need to add a step between, say, ownership assignment and rendering:

1. Define the new domain types in `core/` (the input and output of the new step).
2. Annotate a new `@Action` on the relevant agent — the platform resolves the chain by type, so as long as your new output type matches what the next existing step expects as input, no rewiring is needed.
3. If the step crosses a module boundary (e.g. the new logic belongs in `layout/` rather than `generation/`), expose the action on that module's agent and let the platform plan across agents.
4. Add a `*ModuleTest` to cover the new module's Spring wiring and a focused unit test for the new logic.
5. Update this doc's pipeline diagram and the per-stage table.

Where to put cross-cutting code:

- Pure domain types and validation rules: `core/`.
- LLM-bound logic: the agent under `generation/` or `repair/`.
- Deterministic post-processing of XML: `layout/`.
- Anything that should fire on validation outcomes: a new listener in `observability/`.

## Why this shape

A few design choices that aren't obvious from reading individual classes:

- **The LLM only runs in two places** (outline generation, repair prompts). Everything else is deterministic. This keeps the failure surface small and makes the system testable without mocking the model for most cases.
- **Repair is local-first by construction**, not by convention. The `@Order` on each strategy + the `RepairKind` stamping in the normalizer make it impossible to route a `LOCAL_*_FIX` diagnostic to the LLM strategy before local has had a chance. The architecture document for the repair sub-system explains why the contract is a single `RepairKind` enum rather than the older two-axis `repairRoute × editSurface`.
- **Layout is a terminal stage, not part of the loop.** A semantic repair that happens to break layout would otherwise be undetectable until production. By separating "make it semantically valid" from "make it visually valid" and validating both at the end, the boundary is enforceable.
- **Stuck-state detection uses fingerprints, not attempt counts.** Two identical diagnostic fingerprints in a row abort the loop with a clear "stuck" reason. This catches the worst failure mode — the LLM oscillating between two invalid states — without waiting for `maxAttempts` to exhaust.
