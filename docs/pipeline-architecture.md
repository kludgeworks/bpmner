# BPMN Generation Pipeline Architecture

`bpmner` turns a plain-language business-process description into validated BPMN 2.0 XML. A single orchestrating agent, `BpmnGenerationAgent`, drives the whole happy path over a sequence of strongly-typed domain objects. Each step narrows the failure mode: the LLM owns *what* the process means; deterministic Kotlin code owns *how* it is structured, laid out, and validated.

This document maps the end-to-end pipeline. The Embabel GOAP planner — how the orchestrator's actions are chained, how failure modes surface as typed exceptions — has its own deeper dive in [`goap-lifecycle.md`](./goap-lifecycle.md). The Pkl repair contract (RepairKind, RepairSafety, handler registration) is documented there too.

> The pipeline used to be a chain of per-stage `@Agent` classes (gate, contract, generator, repair, alignment) plus an iterative repair GOAP loop. Those were consolidated into the single `BpmnGenerationAgent` orchestrator; the old per-stage logic now lives behind public ports. The validation stage runs an iterative repair loop inside the `BpmnRepairer` port, driven by Embabel's `RepeatUntilAcceptable` mechanism (see [Stage: Validate](#stage-validate-repair)).

## Module map

The codebase is a Spring Modulith application under `dev.groknull.bpmner.*`. Each module owns one bounded responsibility; cross-module dependencies are restricted to the public package of each module. The *structural* shape of each module — ports, adapters, domain services, and the jMolecules annotations that mark each role — is documented separately in [`hexagonal-architecture.md`](./hexagonal-architecture.md).

| Module | Owns | Key public types |
| --- | --- | --- |
| `api/` | Stable cross-cutting enums/value types shared with the rules surface. | `RepairKind`, `RepairSafety`, `RuleMetadata`. |
| `domain/` | Pure BPMN graph kernel and cross-tier request/render DTOs. | `BpmnRequest`, `BpmnDefinition`, `LaidOutProcessGraph`, `RenderedBpmn`, `BpmnElementIndex`. |
| `orchestration/` | The single `generateBpmn` orchestrator: action shims that delegate to each module's public port. | `BpmnGenerationAgent`. |
| `readiness/` | Guardrail 1: heuristic + LLM input assessment, ready-state handoff, scoped readiness sub-process. | `BpmnReadinessAgent`, `BpmnReadinessInvoker` (port), `AgentPlatformBpmnReadinessInvoker`, `ProcessInputAssessment`, `ReadinessVerdict`, `ReadyBpmnContext`. |
| `contract/` | Guardrail 2: extraction of source-grounded process contracts, multi-source evidence tracking. | `ProcessContractExtractor` (port), `LlmProcessContractExtractor`, `ProcessContract`, `ValidatedProcessContract`. |
| `generation/` | Request drafting, typed LLM generation, structural composition, XML rendering, agent invocation, file writing. | `BpmnRequestDrafter` (port), `LlmBpmnRequestDrafter`, `BpmnProcessGenerator` (port), `LlmBpmnProcessGenerator`, `BpmnGraphRenderer`, `BpmnRenderer` (port), `BpmnAgentInvoker`, `AgentPlatformBpmnAgentInvoker`, `BpmnResult`, `BpmnGeneratedEvent`. |
| `repair/` | Validation and iterative repair of the rendered definition. Contract-aware validation wrapper, diagnostic classification, and `RepeatUntilAcceptable` repair loop. | `BpmnRepairer` (port), `DefaultBpmnRepairer`, `BpmnRepairLoop`, `BpmnRepairAdvancer`, `BpmnContractAwareValidator`. |
| `validation/` | Diagnostic discovery: BPMN definition checks, XSD validation, in-process rule-engine evaluation, capability stamping. | `BpmnValidator` (port), `BpmnLintingPort`, `BpmnXsdValidationPort`, `BpmnRuleGuidancePort`, `ValidatedBpmnXml`, `FinalValidatedBpmnXml`, `BpmnValidationFailedEvent`, `BpmnValidationPassedEvent`. |
| `layout/` | Deterministic auto-layout and final post-layout validation. | `BpmnLayoutAgent`, `BpmnLayoutPort` (port), `LayoutedBpmnXml`. |
| `alignment/` | Guardrail 3: semantic comparison of generated BPMN vs process contract, invented-task detection. | `BpmnAligner` (port), `LlmBpmnAligner`, `BpmnAlignmentReport`, `AlignmentVerdict`. |
| `observability/` | Process-finished summary, validation event logging, per-attempt observers, SSE progress projection. | `BpmnerRunSummaryListener`, `BpmnPipelineObserver`, `BpmnProgressProjectionObserver`. |
| `config/` | OpenAI-compatible provider model configuration; startup agent-deployment validation. | `BpmnConfig`, `OpenRouterModelsConfig`, `AgentDeploymentValidator`. |

Module boundaries are verified by `BpmnerModulithTest`; the `internal` adapter packages under each module are not importable from outside.

## End-to-end pipeline

`BpmnGenerationAgent` exposes thirteen thin `@Action` methods. The planner threads them by type from the starting input through to a `BpmnResult`. Every action delegates to a public port (or runs a small inline step); the real work lives behind those ports.

```text
   ┌──────────────────────────┐            ┌────────────────────────────────┐
   │ Shell: generate "<desc>" │            │ Web (Tripper JourneyController)│
   │   → UserInput            │            │   → BpmnRequest  (async,       │
   │                          │            │     INTERACTIVE mode)          │
   └────────────┬─────────────┘            └───────────────┬────────────────┘
                ▼                                          ▼
   ┌───────────────────────────────────────────────────────────────────────┐
   │                  BpmnGenerationAgent  (orchestration/)               │
   │                                                                       │
   │  draft               LLM → BpmnRequestDraft       (BpmnRequestDrafter)│
   │     ▼                                                                 │
   │  resolve             → BpmnRequest                (BpmnRequestResolver)│
   │     ▼                                                                 │
   │  assessReadiness     scoped sub-process → ProcessInputAssessment      │
   │     ▼                                          (BpmnReadinessInvoker)  │
   │  startAssessing      → Assessing  (@State machine entry)              │
   │     ▼  (state machine: Ready → ReadyBpmnContext; AwaitingClarification│
   │         → WaitFor.formSubmission over SSE; Blocked → NEEDS_CLARIF.)  │
   │  extractContract     LLM → ValidatedProcessContract                   │
   │     ▼                                          (ProcessContractExtractor)│
   │  createOutline       LLM → ValidatedOutline      (BpmnProcessGenerator)│
   │     ▼                                                                 │
   │  composeGraph        deterministic → LaidOutProcessGraph              │
   │     ▼                                                                 │
   │  render              → RenderedBpmn  (BpmnGraphRenderer→BpmnRenderer)  │
   │     ▼                  emits BpmnGeneratedEvent                       │
   │  validate            repair loop → ValidatedBpmnXml   (BpmnRepairer)   │
   │     ▼                                                                 │
   │  layout              inline BpmnLayoutPort + XSD → FinalValidatedBpmnXml│
   │     ▼                  throws on XSD-invalid output                   │
   │  align               LLM → BpmnAlignmentReport       (BpmnAligner)    │
   │     ▼                  critique gate: no throw on verdict              │
   │  finish  @AchievesGoal(generateBpmn)  writes file → BpmnResult        │
   │            verdict==FAILED → ALIGNMENT_FAILED (no file write)         │
   └───────────────────────────────────────────────────────────────────────┘
```

The arrows between domain types are exact: each action declares its input and output type via Embabel `@Action`, and the agent platform resolves the chain by type. Inserting a new step is a matter of defining a new type, a port, and an action that produces it.

## Entrypoints

Both entrypoints reach the `generateBpmn` goal by resolving the orchestrator **by name** (`"BpmnGenerationAgent"`) on the `AgentPlatform`:

- **Shell** — `BpmnShellCommands` (`generation/internal/adapter/inbound/`) exposes `generate` / `gen` / `g`. It delegates to Embabel's `execute` in **closed mode**, seeding `UserInput` from the prose; the orchestrator's `draft`/`resolve` actions turn that into a `BpmnRequest`.
- **Web (Tripper `JourneyController`)** — `BpmnWebController` → `WebGenerationStarter` calls the web-only `BpmnAgentInvoker.startAsync(request)` overload. There is **no synchronous readiness pre-check and no HTTP 422**; readiness assessment runs inside the async agent process. `WebGenerationStarter` sets the process mode to `INTERACTIVE`, and the controller returns `202 {processId, sseUrl}`. Clarification surfaces as an in-process `WaitFor.formSubmission` over SSE. The implementation `AgentPlatformBpmnAgentInvoker` (`generation/AgentPlatformBpmnAgentInvoker.kt`) finds the agent named `BpmnGenerationAgent` and seeds the plan with only `BpmnRequest`. The synchronous `generate(request, assessment)` and the legacy `startAsync(request, assessment)` overloads remain for the CLI/shell seam.

`AgentPlatformBpmnAgentInvoker` reads the goal output via `AgentProcessExecution.fromProcessStatus(request, process)`, which returns the `BpmnResult` on `COMPLETED` and throws the framework's typed status exceptions on non-completed states (see [`goap-lifecycle.md`](./goap-lifecycle.md#stuck-vs-terminated)).

## Stage: Generation (`generation/`)

`BpmnProcessGenerator` (port, implemented by `LlmBpmnProcessGenerator`) deliberately splits "ask the LLM" from "structure the result." Only `createOutline` calls the model; everything after it is deterministic code operating on the typed `BpmnDefinition` the model returned.

| Action | Input → Output | What happens |
| --- | --- | --- |
| `createOutline` | `(ReadyBpmnContext, ValidatedProcessContract) → ValidatedOutline` | Builds a generator prompt, asks the LLM for a `BpmnDefinition`, applies `DefaultFlowAssigner` to stamp default outgoing flows on exclusive gateways, and runs an inline fidelity check. Emits a `ValidatedOutline`. |
| `composeGraph` | `ValidatedOutline → LaidOutProcessGraph` | Deterministic: builds the object- and element-ownership maps and wraps them in `LaidOutProcessGraph`. |
| `render` | `(ReadyBpmnContext, LaidOutProcessGraph) → RenderedBpmn` | Delegates to `BpmnGraphRenderer` (a domain `@Component` that holds the `BpmnRenderer` secondary port). `BpmnRenderer` builds semantic XML (no BPMNDI — layout coordinates come later). Emits `BpmnGeneratedEvent`. |

`BpmnGraphRenderer` exists because a `@PrimaryAdapter` may not depend on a `@SecondaryPort` under the hexagonal-architecture rule, so the `BpmnRenderer` port lives behind a plain domain `@Component` and `LlmBpmnProcessGenerator` delegates rendering to it.

### Why a typed `BpmnDefinition`, not raw XML

The LLM produces an object with explicit semantic fields (nodes, sequences). XML rendering happens *after* generation; it's a deterministic step, not the first thing the LLM sees. Diagram coordinates are deliberately absent — they are computed downstream by the auto-layout stage so the LLM can never produce a layout that fights the layout engine.

## Stage: Validate & Repair (repair `/`) {#stage-validate-repair}

The orchestrator's `validate` action delegates to the `BpmnRepairer` port, implemented by `DefaultBpmnRepairer`. It performs an initial evaluation and, if diagnostics remain, executes an iterative `RepeatUntilAcceptable` repair loop (defined in `BpmnRepairLoop`) before returning a `ValidatedBpmnXml`:

```text
(graph, rendered, contract) ──► BpmnRepairAdvancer.initialEvaluation
        │
        ├── [Already clean] ───────────────────────────────────────────┐
        │                                                              │
        └── [Diagnostics remain] ──► BpmnRepairLoop (RUA sub-process) ──┼──► ValidatedBpmnXml
                                            │                          │
                                            ├── LocalFixApplier        │
                                            ├── LlmLabelPatch          │
                                            ├── LlmStructuralPatch     │
                                            └── FullLlmRewrite         │
```

The repair loop uses cost-aware escalation to select and apply fixes (Deterministic Local → LLM Label → LLM Structural → Full LLM Rewrite). It exits early if all blocking diagnostics are resolved, or terminates after exhausting the configured `maxRepairIterations`.

`DefaultBpmnRepairer.validateInitial(...)` publishes a `BpmnValidationPassedEvent` when the final evaluation is clean and a `BpmnValidationFailedEvent` when diagnostics remain. The Pkl repair contract is documented in [`goap-lifecycle.md`](./goap-lifecycle.md#the-pkl-side-repair-contract).

## Stage: Layout (inline; `layout/`)

The orchestrator's `layout` action runs auto-layout **inline** — it does *not* route through `BpmnLayoutAgent`:

```text
ValidatedBpmnXml
   │  BpmnLayoutPort.layout(xml)              embedded bpmn-auto-layout via GraalJS
   ▼
LayoutedBpmnXml
   │  BpmnXsdValidationPort.validateDetailed  XSD compliance
   ▼  (error() on any XSD issue)
FinalValidatedBpmnXml
```

`BpmnLayoutPort` runs the embedded `bpmn-auto-layout` JS bundle in GraalJS to assign deterministic diagram coordinates (waypoints, shape bounds). The action then XSD-validates the result and throws (`error(...)`) if the auto-layout pass produced structurally invalid BPMN — a layout-engine bug, not something the LLM is asked to fix. The action is `FIRE_ONCE`.

`BpmnLayoutAgent` still exists as a standalone, separately-invokable layout agent (`layoutBpmnXml` + `validateFinalBpmnXml`, achieving the `finalizeLayout` goal), but it is not part of the orchestrator's plan. See [`agents.md`](./agents.md#bpmnlayoutagent--standalone-layout-agent).

## Stage: Alignment (`alignment/`)

The `align` action delegates to the `BpmnAligner` port (`LlmBpmnAligner`): an LLM compares the final BPMN against the `ValidatedProcessContract`, flagging invented tasks, missing branches, or unsupported end states. It returns a `BpmnAlignmentReport` — a **critique gate**, not a throwing step. The action is `FIRE_ONCE` — a model failure on alignment is not retried. The `finish` action reads the report: if `verdict == FAILED` it returns `BpmnResult(status = ALIGNMENT_FAILED)` without writing a file; otherwise it writes the output and returns `BpmnResult(status = GENERATED)`.

## Stage: Finish (`orchestration/`)

The `finish` action carries `@AchievesGoal(name = "generateBpmn")` — the top-level goal. It writes the final XML to the requested output file (mkdirs the parent, skips blank paths), and returns `BpmnResult(status = GENERATED, xml, alignmentReport)`. It is `FIRE_ONCE`.

## Validation as a shared service

`validation/` is consumed by the `validate` stage (through `BpmnContractAwareValidator` wrapping the `BpmnValidator` pipeline) and by the `layout` stage (through `BpmnXsdValidationPort`). It owns:

- `BpmnDefinitionValidator` — structural invariants on the typed model.
- `BpmnXsdValidator` — strict BPMN 2.0 XSD compliance (behind `BpmnXsdValidationPort`).
- `RuleEngineLintingAdapter` — implements `BpmnLintingPort` by delegating to the `rules` module's rule engine and projecting rule metadata into lint-issue shapes.
- `BpmnDiagnosticNormalizer` — looks up the Pkl-declared capability and stamps each diagnostic with `kind` (`RepairKind`), `repairSafety`, and `fixHandler`; infers `repairScope` from ownership context.

A startup capability guard — `BpmnLocalRepairCapabilityValidator` (`repair/internal/domain/`) — fails the Spring context refresh if any `LOCAL_MODEL_FIX` rule names a handler that isn't registered. `AgentDeploymentValidator` (`config/`) performs an analogous startup check on deployed agents.

The `validation`/`repair` path emits `BpmnValidationPassedEvent` and `BpmnValidationFailedEvent`; `observability/` listeners turn those into log lines and per-run summaries.

## Configuration

`BpmnConfig` (`@ConfigurationProperties("bpmner")`) controls the pipeline at runtime. For the full configuration reference (every `bpmner.*` YAML key, defaults, ranges, and when to tune) see [`operator-guide.md`](./operator-guide.md). The high-level surface:

| Property | Default | Effect |
| --- | --- | --- |
| `bpmner.budget.generation` | `100` | `Budget(actions = N)` ceiling for the `generateBpmn` goal. |
| `bpmner.budget.readiness` | `20` | Tighter budget for the scoped readiness sub-process. |
| `bpmner.rules.profile` | `recommended` | Named rule profile loaded at startup. `strict` bumps every WARNING-default rule to ERROR. |
| `bpmner.rules.severity-overrides` | `{}` | Per-rule severity escape hatch applied on top of the active profile. |
| `bpmner.generator` / `bpmner.contractExtractor` / … | role-based personas | Each `Actor<Persona>` carries a `Persona` + `LlmOptions.withLlmForRole(...)` slot. |
| `bpmner.logging.dump-artifacts` | `false` | When `true`, emits truncated previews of every intermediate artifact at DEBUG. |

Model role bindings (`generator`, `contract-extractor`, `readiness-assessor`, `alignment-validator`, `linter`, and the `repairer` / `repair-label` / `repair-patch` / `repair-rewrite` slots) are resolved by the active Spring profile (`anthropic`, `openai`, `gemini`, `mistral`, `deepseek`, or `llama`). See the top-level `README.md` for invocation.

## Observability surface

- **`BpmnerRunSummaryListener`** — one line per agent process completion: total time, action count, models used, total cost, prompt/completion tokens, plus a per-action timing breakdown.
- **`BpmnPipelineObserver`** — listens on `BpmnValidationFailedEvent` / `BpmnValidationPassedEvent`. Per-attempt summary of source-grouped diagnostic counts.
- **`BpmnProgressProjectionObserver`** — maps `@Action` start events to user-facing labels and republishes them as SSE progress events for the web UI.

All use SLF4J at INFO with bracketed parameter placeholders, so they are queryable with grep or log aggregation without per-call string formatting.

## Adding a new pipeline stage

To add a step between, say, composition and rendering:

1. Define the new domain types in `domain/` (or the owning module) for the step's input and output.
2. Put the real logic behind a public **port** in the owning module (a `@PrimaryAdapter @Component` if LLM-backed, a plain `@Component` otherwise).
3. Add a thin `@Action` to `BpmnGenerationAgent` that delegates to the port — the platform resolves the chain by type, so as long as your output type matches the next existing step's input, no rewiring is needed.
4. Add a `*ModuleTest` to cover the new module's Spring wiring and a focused unit test for the new logic.
5. Update this doc's pipeline diagram and the per-stage section, plus `BpmnProgressProjectionObserver.ACTION_LABELS`.

Where to put cross-cutting code:

- Pure domain types and validation rules: `domain/`, `api/`, `validation/`, `rules/`.
- LLM-bound logic: a `@PrimaryAdapter` port impl in the owning module.
- Deterministic post-processing of XML: `layout/`.
- Anything that should fire on validation outcomes: a new listener in `observability/`.

## Why this shape

- **One orchestrator, thin actions.** The happy path is a single linear plan on `BpmnGenerationAgent`; each action is a typed shim over a port. The logic is testable per-port without standing up the whole agent platform.
- **The LLM runs in a few bounded places** (request drafting, readiness, contract extraction, outline generation, alignment). Everything else is deterministic, which keeps the failure surface small.
- **Layout is a terminal, deterministic stage.** Auto-layout runs after validation and is XSD-checked; a layout-engine corruption throws rather than re-entering generation.
- **Validation runs an iterative repair loop.** The orchestrator invokes `BpmnRepairer` which drives the `RepeatUntilAcceptable` loop across the cost-tiered repair appliers until the definition is clean or the iteration budget is exhausted.
