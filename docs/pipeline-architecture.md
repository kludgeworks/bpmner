# BPMN Generation Pipeline Architecture

`bpmner` turns a plain-language business-process description into validated BPMN 2.0 XML by chaining three Embabel agents — generation, repair, layout — over a sequence of strongly-typed domain objects. Each stage narrows the failure mode: the LLM owns *what* the process means; deterministic Kotlin code owns *how* it is structured, laid out, and validated.

This document maps the end-to-end pipeline. The repair sub-system has its own deeper dive in [`linter/docs/repair-architecture.md`](../linter/docs/repair-architecture.md).

## Module map

The codebase is a Spring Modulith application under `dev.groknull.bpmner.*`. Each module owns one bounded responsibility; cross-module dependencies are restricted to the public package of each module. The *structural* shape of each module — ports, adapters, domain services, and the jMolecules annotations that mark each role — is documented separately in [`hexagonal-architecture.md`](./hexagonal-architecture.md).

| Module | Owns | Key public types |
| --- | --- | --- |
| `core/` | Shared domain model, configuration, fingerprints, naming policy. No Spring visibility restrictions. | `BpmnRequest`, `BpmnDefinition`, `BpmnConfig`, `BpmnDiagnostic`, `RepairKind`, `LaidOutProcessGraph`, `RenderedBpmn`, `ValidatedBpmnXml`, `FinalValidatedBpmnXml`, `BpmnResult`. |
| `generation/` | LLM-driven typed generation, structural composition, ownership assignment, XML rendering, file writing. | `BpmnGeneratorAgent`, `BpmnRenderer` (port), `BpmnGeneratedEvent`. |
| `validation/` | Diagnostic discovery: BPMN definition checks, XSD validation, bpmn-lint via GraalJS, capability stamping. | `BpmnValidator` (port), `BpmnLintingPort`, `BpmnXsdValidationPort`, `BpmnRuleGuidancePort`, `BpmnValidationFailedEvent`, `BpmnValidationPassedEvent`. |
| `repair/` | Local-first deterministic repair, LLM patch / rewrite strategies, refinement loop with stuck-state detection. | `BpmnRepairAgent`, `BpmnRefinementEngine` (internal), `BpmnLocalFixSummary`. |
| `layout/` | Bounded pre-layout XML cleanup, deterministic auto-layout, final post-layout validation. | `BpmnLayoutAgent`, `BpmnLayoutPort`. |
| `observability/` | Process-finished summary, validation event logging, per-attempt observers. | `BpmnerRunSummaryListener`, `BpmnPipelineObserver`. |
| `shell/` | `generate` / `gen` Spring Shell commands. | `BpmnShellCommands`. |
| `config/` | GitHub Models / Anthropic model configuration. | `GitHubModelsConfig`, `GitHubCatalogClient`. |

Module boundaries are verified by `BpmnerModulithTest`; the `internal` adapter packages under each module are not importable from outside.

## End-to-end pipeline

```
                              ┌──────────────────────┐
                              │     BpmnRequest      │
                              │  (process + style)   │
                              └──────────┬───────────┘
                                         ▼
            ┌─────────────────────────────────────────────────────────┐
            │           BpmnGeneratorAgent  (generation/)             │
            │                                                         │
            │  createProcessOutline  ── LLM ──► BpmnDefinition        │
            │              │                                          │
            │              ▼                                          │
            │       ProcessOutline ─► validateOutline                 │
            │              │                                          │
            │              ▼                                          │
            │       ValidatedOutline ─► generatePhasePlans            │
            │              │                                          │
            │              ▼                                          │
            │       PhasePlanSet ─► validatePhasePlans                │
            │              │                                          │
            │              ▼                                          │
            │       ValidatedPhasePlanSet ─► composeProcessGraph      │
            │              │                                          │
            │              ▼                                          │
            │       ComposedProcessGraph ─► assignOwnership           │
            │              │                                          │
            │              ▼                                          │
            │       OwnedElementGraph ─► assignLayout                 │
            │              │                                          │
            │              ▼                                          │
            │       LaidOutProcessGraph ─► renderBpmnXml              │
            │              │                                          │
            │              ▼                                          │
            │       RenderedBpmn ────────────► BpmnGeneratedEvent     │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
            ┌─────────────────────────────────────────────────────────┐
            │            BpmnRepairAgent  (repair/)                   │
            │                                                         │
            │  refine ── BpmnRefinementEngine ──────────────►         │
            │              loop  (≤ maxAttempts)                      │
            │              ├─ evaluate  (validation/)                 │
            │              │    │ definition checks                   │
            │              │    │ ownership checks                    │
            │              │    │ XSD validation                      │
            │              │    │ bpmn-lint (SEMANTIC_PRE_LAYOUT)     │
            │              │    │ normalize + stamp RepairKind        │
            │              │    ▼                                     │
            │              ├─ repair strategies, in @Order:           │
            │              │    LintLocalRepairStrategy        (75)   │
            │              │    LlmPatchRepairStrategy         (200)  │
            │              │    FullLlmRewriteRepairStrategy   (300)  │
            │              │                                          │
            │              ├─ per-attempt route-summary log line      │
            │              ├─ stuck/unchanged diagnostic detection    │
            │              └─ publish BpmnValidationPassed/Failed     │
            │                                                         │
            │                ──► ValidatedBpmnXml                     │
            └──────────────┬──────────────────────────────────────────┘
                           ▼
            ┌─────────────────────────────────────────────────────────┐
            │            BpmnLayoutAgent  (layout/)                   │
            │                                                         │
            │  autoFixBpmnXml                                         │
            │     │ pre-layout cleanup limited to LOCAL_XML_FIX       │
            │     │ XSD-validates output; falls back on rejection     │
            │     ▼                                                   │
            │  AutoFixedBpmnXml ─► layoutBpmnXml                      │
            │     │ deterministic layout via embedded bpmn-auto-layout│
            │     ▼                                                   │
            │  LayoutedBpmnXml ─► validateFinalBpmnXml                │
            │     │ XSD + bpmn-lint (FINAL_POST_LAYOUT)               │
            │     │ throws if any diagnostic remains                  │
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

`BpmnGeneratorAgent` deliberately splits "ask the LLM" from "structure the result." Only `createProcessOutline` calls the model; everything after it is deterministic code operating on the typed `BpmnDefinition` the model returned.

| Action | Input → Output | What happens |
| --- | --- | --- |
| `createProcessOutline` | `BpmnRequest → ProcessOutline` | Builds a generator prompt from `BpmnRequest.generationPrompt()`, attaches the request as a prompt contributor (rules + optional style guide), calls `promptRunner.createObject(BpmnDefinition::class.java)`. Returns the `BpmnDefinition` plus `OutlineMetrics` (phase / branch / loop / subprocess counts). |
| `validateOutline` | `ProcessOutline → ValidatedOutline` | Cheap structural sanity check: non-blank `processId` and `processName`. Failures become `BpmnDiagnostic`s with `repairScope = OUTLINE`. |
| `generatePhasePlans` | `ValidatedOutline → PhasePlanSet` | Currently emits a single `phase:main` plan covering the whole process. Reserved for future multi-phase decomposition. |
| `validatePhasePlans` | `PhasePlanSet → ValidatedPhasePlanSet` | Per-phase validation hook; today a no-op pass-through with empty diagnostics. |
| `composeProcessGraph` | `ValidatedPhasePlanSet → ComposedProcessGraph` | Builds the `objectOwnersByObjectRef` map: every node and sequence gets stamped with its owning phase. This is the foundation for `repairScope` inference downstream. |
| `assignOwnership` | `ComposedProcessGraph → OwnedElementGraph` | Mirrors the object-ref ownership into an `elementOwnersByElementId` map, including `_di` shape ids. The diagnostic normalizer uses this to resolve `ownerRef` from `elementId`. |
| `assignLayout` | `OwnedElementGraph → LaidOutProcessGraph` | Passes through; the layout coordinates come from the LLM output (every node has explicit `bounds`, every edge has `waypoints`). Reserved for a future deterministic layout pass. |
| `renderBpmnXml` | `(BpmnRequest, LaidOutProcessGraph) → RenderedBpmn` | `BpmnRenderer` (Camunda model API) builds the XML and a `BpmnElementIndex` mapping element ids to render objects and `_di` shape ids. Emits `BpmnGeneratedEvent`. |
| `writeBpmn` (final step) | `(BpmnRequest, FinalValidatedBpmnXml) → BpmnResult` | UTF-8 writes the final XML to the requested output file. Carries `@AchievesGoal` for the agent platform's planner. |

### Why a typed `BpmnDefinition`, not raw XML

The LLM produces an object with explicit fields (nodes, sequences, bounds, waypoints). This makes deterministic repair possible: a model-level patch like `SET_NODE_NAME(id, name)` operates on the typed graph, not a regex on XML. XML rendering happens *after* all validation and repair; it's the last deterministic step, not the first thing the LLM sees.

## Stage 2 — Repair (`repair/`)

`BpmnRepairAgent` is a thin facade over `BpmnRefinementEngine`, the orchestration loop.

```
RenderedBpmn ──► BpmnRefinementEngine.refine ──► ValidatedBpmnXml
                          │
                          ▼
                 BpmnEvaluationPipeline.evaluate(...)
                          │
              ┌───────────┴───────────────┐
              │  BpmnDefinitionValidator  │   structural sanity
              │  graph.validateOwnership  │   ownership integrity
              │  BpmnXsdValidationPort    │   schema compliance
              │  BpmnLintingPort.lint     │   bpmn-lint diagnostics
              │  BpmnDiagnosticNormalizer │   stamps RepairKind on each
              └───────────────────────────┘
                          │
                          ▼
                 if successful → ValidatedBpmnXml
                 else  →  pick first applicable strategy
                          │
                          ▼
              ┌───────────────────────────────────────────┐
              │  LintLocalRepairStrategy       (Order 75) │
              │   LOCAL_MODEL_FIX → Kotlin model patcher  │
              │   LOCAL_XML_FIX   → TS auto-fix engine    │
              │   records BpmnLocalFixSummary             │
              ├───────────────────────────────────────────┤
              │  LlmPatchRepairStrategy        (Order 200)│
              │   prompts LLM for BpmnRepairPatch         │
              │   excludes locally-resolved diagnostics   │
              │   annotates locally-failed ones           │
              ├───────────────────────────────────────────┤
              │  FullLlmRewriteRepairStrategy  (Order 300)│
              │   prompts LLM for whole BpmnDefinition    │
              └───────────────────────────────────────────┘
                          │
                          ▼
              re-render → re-evaluate → next attempt
              (≤ maxAttempts; fingerprint-based stuck detection)
```

Key invariants enforced by the loop:

- **Local-first.** Strategies execute in `@Order`. Deterministic local fixes always run before any LLM call; the LLM only sees diagnostics that local routes either declined (kind ∈ `{LLM_MODEL_PATCH, LLM_XML_REWRITE, null}`) or failed to fix.
- **No re-asking the LLM about resolved issues.** A locally-applied fix is removed from the next attempt's diagnostic pool by re-linting; the prompt never contains it.
- **Fail loud on stuck states.** Each attempt produces a `BpmnAttemptRecord` with a diagnostic fingerprint; two consecutive identical fingerprints abort the loop with a structured failure summary rather than burning the budget.
- **Audit per attempt.** `BpmnRefinementEngine` logs `Repair attempt N route summary: total=… localAttempted=… localApplied=… localFailed=… llmRouted=… unfixable=…` so routing decisions are visible in normal logs.

The repair contract itself — what each `RepairKind` means, where capabilities come from, how Pkl → Bazel → JVM/TS feeds the loop — is documented in [`linter/docs/repair-architecture.md`](../linter/docs/repair-architecture.md).

## Stage 3 — Layout (`layout/`)

`BpmnLayoutAgent` runs after the repair loop has produced semantically valid XML. It is deliberately narrow: it neither re-runs the repair loop nor invokes the LLM.

| Action | Input → Output | What happens |
| --- | --- | --- |
| `autoFixBpmnXml` | `ValidatedBpmnXml → AutoFixedBpmnXml` | Bounded pre-layout cleanup. Re-lints in `SEMANTIC_PRE_LAYOUT` phase, filters issues to those whose capability `kind` is `LOCAL_XML_FIX`, applies the TS auto-fix bundle, XSD-validates the result. If the auto-fixed XML is XSD-invalid, the original validated XML is kept. |
| `layoutBpmnXml` | `AutoFixedBpmnXml → LayoutedBpmnXml` | `BpmnLayoutPort` runs the embedded `bpmn-auto-layout` JS bundle in GraalJS to assign deterministic diagram coordinates (waypoints, shape bounds). |
| `validateFinalBpmnXml` | `LayoutedBpmnXml → FinalValidatedBpmnXml` | Final XSD pass plus `FINAL_POST_LAYOUT` lint. Layout-sensitive rules (declared in Pkl with `layoutSensitive = true`) are evaluated here, not earlier. Any remaining diagnostic throws `BpmnFinalValidationException` — the agent does **not** re-enter repair. |

Final validation is intentionally terminal. Anything still wrong after layout is a bug in either the layout engine, a layout-sensitive rule, or the contract between them — not something the LLM should be asked to fix.

## Validation as a shared service

`validation/` is consumed by both the repair loop (via `BpmnValidator.evaluate`) and the layout agent (via `BpmnLintingPort.lint` and `BpmnXsdValidationPort.validateDetailed`). It owns:

- `BpmnDefinitionValidator` — structural invariants on the typed model.
- `BpmnXsdValidator` — strict BPMN 2.0 XSD compliance.
- `BpmnLintService` — GraalJS-hosted bpmn-lint bundle, with a phase argument so semantic rules run pre-layout and layout-sensitive rules run post-layout.
- `BpmnDiagnosticNormalizer` — strips rule-id prefixes, looks up the Pkl-declared capability, and stamps each lint diagnostic with `kind`, `repairSafety`, and `fixHandler`; infers `repairScope` from ownership context.
- `PklRuleCapabilityAdapter` + `RuleCatalogService` — load the Pkl-generated catalog once at startup; the loaded map is the single source of truth for routing.
- `BpmnLocalRepairCapabilityValidator` — startup guard: any `LOCAL_*_FIX` capability whose handler isn't registered fails the Spring context refresh.

The `validation` module emits `BpmnValidationPassedEvent` and `BpmnValidationFailedEvent`; `observability/` listeners turn those into log lines and per-run summaries.

## Configuration

`BpmnConfig` (`@ConfigurationProperties("bpmner")`) controls the pipeline at runtime:

| Property | Default | Effect |
| --- | --- | --- |
| `bpmner.max-attempts` | `5` | Hard cap on repair iterations (initial evaluation + repairs). |
| `bpmner.generator` | `BPMN Designer` persona; `LlmOptions.withLlmForRole("generator")` | Persona + model role for outline generation. |
| `bpmner.repairer` | `BPMN Repair Specialist` persona; `LlmOptions.withLlmForRole("repairer")` | Persona + model role for repair prompts. |
| `bpmner.logging.dump-artifacts` | `false` | When `true`, emits truncated previews of every intermediate artifact at DEBUG. |
| `bpmner.logging.artifact-preview-length` | `8000` | Cap on the dumped preview. |
| `bpmner.repair.abbreviations` | `{}` | Replacement map fed into the abbreviation auto-fix handler. |

Model role bindings (`generator`, `repairer`) are resolved by the active Spring profile — `anth` for Anthropic, `gh` for GitHub Models / OpenAI-compatible. See the top-level `README.md` for invocation.

## Observability surface

Three layers, increasing in granularity:

- **`BpmnerRunSummaryListener`** — one line per agent process completion: total time, action count, models used, total cost, prompt/completion tokens, plus a per-action timing breakdown.
- **`BpmnPipelineObserver`** — listens on `BpmnValidationFailedEvent` / `BpmnValidationPassedEvent`. Per-attempt summary of source-grouped diagnostic counts.
- **`BpmnRefinementEngine`** — single structured line per repair attempt with the route summary (`localAttempted` / `localApplied` / `localFailed` / `llmRouted` / `unfixable`). Plus an `error` summary on terminal failure with the stuck fingerprint(s) and top diagnostics.

All three use SLF4J at INFO with bracketed parameter placeholders, so they are queryable with grep or log aggregation without per-call string formatting.

## Where each acceptance check lives

For someone reading this doc to find the test that proves an invariant:

| Invariant | Verified by |
| --- | --- |
| Generator outputs a typed `BpmnDefinition`, not raw XML. | `BpmnGenerationServiceTest`, `BpmnGeneratorRunnerTest`. |
| Phase composition preserves ownership. | `BpmnOwnershipTest`, `BpmnRefinementEngineTest`. |
| Validation pipeline normalises rule prefixes and stamps `kind`. | `BpmnDiagnosticNormalizerTest`, `BpmnValidationIntegrationTest`. |
| Local-first repair runs before any LLM call. | `BpmnRefinementEngineTest`, `RepairRoutingModuleTest`. |
| LLM prompts only contain unresolved diagnostics. | `BpmnRepairPromptFactoryTest`, `RepairRoutingModuleTest`. |
| Startup fails when a declared-local rule has no handler. | `BpmnLocalRepairCapabilityValidatorTest`, `RepairStartupValidationTest`. |
| Layout agent does not re-enter the repair loop. | `BpmnLayoutAgentTest`. |
| Final-validation failures throw rather than retry. | `BpmnLayoutAgentTest`, `BpmnAgentFlowSystemTest`. |
| End-to-end flow writes the requested file. | `BpmnAgentFlowSystemTest`. |

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
