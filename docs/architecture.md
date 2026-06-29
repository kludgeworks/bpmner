# bpmner — Architecture

> **Status:** Consolidated in epic #424 S7 (ADR-22). This is the single authoritative
> architecture document. The four legacy docs (`agents.md`, `goap-lifecycle.md`,
> `pipeline-architecture.md`, `hexagonal-architecture.md`) have been folded into this
> file and deleted. Do not edit the frozen ADRs
> ([adr-001-single-agent-design.md](./adr-001-single-agent-design.md),
> [adr-002-module-architecture.md](./adr-002-module-architecture.md)).

---

## 1. Context map

Three bounded contexts, one application layer, delivery adapters, and cross-cutting
infrastructure. Decided in [adr-002-module-architecture.md §D-map](./adr-002-module-architecture.md).

<!-- markdownlint-disable MD013 -->

| Context / Layer | Role | Owns | Current modules (as-built) |
| --- | --- | --- | --- |
| **Authoring** | Core domain | The BPMN process graph as a behaviour-bearing domain object; its structural invariants; generation / contract drafting | `domain`, `generation`, `contract`, `layout` |
| **Conformance** | Supporting domain | Rule catalogue + evaluation + repair; own ubiquitous language (rule id / severity / capability, Pkl-fed) | `rules`, `validation`, `repair`, `alignment` |
| **Intake / Readiness** | Supporting domain | Request readiness + clarification subdomain | `readiness` |
| **Generation Orchestration** | Application layer — not a domain context | Single `BpmnGenerationAgent`, GOAP wiring, `@Action` shims | `orchestration` |
| **Delivery adapters** | Inbound/primary adapters — not contexts | HTTP, shell entrypoint | `web`, shell entrypoint |
| **Preview** | Output artifact generator — not a domain context | BPMN → transient temp-dir HTML preview artifact; bundled local viewer assets | `preview` |
| **Cross-cutting** | Infrastructure / sink — not contexts | Config, observability | `config`, `observability` |

<!-- markdownlint-enable MD013 -->

Key decisions:

- `web` (3 files, ~104 lines of HTTP↔port glue) is a **driving/primary adapter** of
  Generation Orchestration, not a bounded context.
- The BPMN process graph is a **behaviour-bearing domain object** — model-intrinsic
  invariants (`validateStructure()`, `validateOwnership()`) live on graph types in `domain`.
  No aggregate/repository/persistence machinery.
- `api` is the **published external contract** (annotation-free, `allowedDependencies = []`).
  All annotation-free BPMN language types live here; implementations live in `domain`.
- "Authoring / Conformance / Intake / Render" are **subdomain labels** (grouping lenses),
  not separate directory structures.

### Module dependency overview (post-S6 grants)

The grant graph is the S6-audited set plus the `preview` module added in epic #476-1:
`layout=["domain","validation"]`, `web=["api","domain","generation"]`,
`readiness=["api","config","domain"]`,
`contract=["api","config","domain","readiness"]`,
`alignment=["api","config","contract","domain","readiness","validation"]`,
`repair=["api","config","contract","domain","generation","readiness","rules","validation"]`,
`preview=[]`.

---

## 2. Module shape, ports, and hexagonal layering

Every Kotlin module in `dev.groknull.bpmner.*` is shaped as a hexagon: a small set of
**ports** (interfaces declared in the module's public package) and a private interior
of **adapters** and **domain services** under `internal/`. The roles are made explicit
with jMolecules annotations, enforced at build time by `BpmnerArchitectureTest`.

### The four roles

| Annotation | Role | Plain-English meaning |
| --- | --- | --- |
| `@PrimaryPort` | Inbound port (use-case API) | What this module *offers* the rest of the system. |
| `@PrimaryAdapter` | Inbound adapter | A specific way an outside party triggers the module (CLI command, Spring Shell, event listener, agent action). |
| `@SecondaryPort` | Outbound port (SPI) | What this module *needs* from the outside world to do its job. |
| `@SecondaryAdapter` | Outbound adapter | A specific implementation of an SPI, bound to a concrete technology (GraalJS, Camunda, file I/O). |

All four come from `org.jmolecules.architecture.hexagonal`. The DDD building blocks
(`@Service`, `@DomainEvent`) come from `org.jmolecules.ddd.annotation` and
`org.jmolecules.event.annotation`.

### Module layout pattern

```text
<module>/
├── <PrimaryPort>.kt          # @PrimaryPort interfaces (use-cases)
├── <SecondaryPort>.kt        # @SecondaryPort interfaces (SPIs)
├── <DomainEvent>.kt          # @DomainEvent classes (cross-module events)
└── internal/
    ├── adapter/
    │   ├── inbound/          # @PrimaryAdapter classes (triggers)
    │   └── outbound/         # @SecondaryAdapter classes (integrations)
    └── domain/               # @Service (DDD) classes (use-case implementations)
```

| Component | Default location | Required annotations | Spring stereotype |
| --- | --- | --- | --- |
| Primary Port (API) | `<module>/` (public package) | `@PrimaryPort` | (none — it's an interface) |
| Secondary Port (SPI) | `<module>/` *or* `<module>/internal/domain/` | `@SecondaryPort` | (none — it's an interface) |
| Domain Service | `<module>/internal/domain/` | `@Service` (jMolecules DDD) | `@Component` |
| Inbound Adapter | `<module>/internal/adapter/inbound/` | `@PrimaryAdapter` | `@Component` (or `@ShellComponent`, `@Agent`, etc.) |
| Outbound Adapter | `<module>/internal/adapter/outbound/` | `@SecondaryAdapter` | `@Component` or `@Service` |

`domain/` and `config/` use no hexagonal annotations — `domain/` holds pure BPMN graph
kernel and cross-tier DTOs; `config/` holds Spring `@ConfigurationProperties` and platform
config. Neither owns a use-case.

### Where the pattern bends

- **Secondary ports inside `internal/domain/`** — `repair/internal/domain/BpmnRepairPorts.kt`
  holds `@SecondaryPort internal interface BpmnRepairPromptPort` and `BpmnPatchApplicationPort`.
  These are private to the module; placing them at root would advertise a contract nothing
  outside the module is allowed to fulfil.
- **`observability/` has no `@SecondaryPort`** — it is a one-way sink: event listeners only,
  no SPI.

### When to use which annotation

- **Adding a new way to trigger generation** — add a primary adapter for the new transport,
  start an Embabel process through `BpmnAgentInvoker` or seed the same blackboard types.
- **Adding a new validator** — `@Service` in `validation/internal/domain/`.
- **Swapping the rule engine** — write a new `@SecondaryAdapter` implementing `BpmnLintingPort`.
- **Adding a brand-new module** — create `<module>/` with its `@PrimaryPort`, `@SecondaryPort`,
  `internal/domain/`, and `internal/adapter/{inbound,outbound}/` skeleton; the architecture
  and Modulith tests pick it up without configuration.
- **Cross-module event** — `@DomainEvent` in the emitting module's public package; listeners
  are `@PrimaryAdapter` in consuming modules.

---

## 3. Agent design and control flow

bpmner is built around a single orchestrating Embabel `@Agent` plus two narrow support
agents. The orchestrator owns the end-to-end `generateBpmn` goal; each of its `@Action`
methods is a thin shim that delegates to a public **port**, and the real work lives behind
those ports in plain Spring components.

### The three agents

<!-- markdownlint-disable MD013 -->

| Agent | File | Actions | Achieves goal | Notes |
| --- | --- | --- | --- | --- |
| `BpmnGenerationAgent` | `orchestration/internal/adapter/inbound/BpmnGenerationAgent.kt` | 15 typed shims: `draft`, `resolve`, `assessReadiness`, `startAssessing`, `extractContract`, `createOutline`, `composeGraph`, `render`, `validate`, `layout`, `align`, `finish`, `reassess`, `proceed`, `terminate` | `generateBpmn` (on `finish`, `terminate` or `Blocked.terminate`) | The single orchestrator. Each action delegates to a public port; `finish` writes the output file and returns `BpmnResult`. |
| `BpmnReadinessAgent` | `readiness/internal/adapter/inbound/BpmnReadinessAgent.kt` | 1: `assessReadiness` (`BpmnRequest → ProcessInputAssessment`) | `assessReadiness` | Invoked as a **scoped sub-process** by the orchestrator's `assessReadiness` action, not chained into the main plan. Style-guide prompt contribution is applied locally via `PromptContributor.fixed(request.styleGuideContribution())` (ADR-21 Track A). |
| `BpmnLayoutAgent` | `layout/internal/adapter/inbound/BpmnLayoutAgent.kt` | 2: `layoutBpmnXml`, `validateFinalBpmnXml` | `finalizeLayout` | Standalone layout agent (GraalJS auto-layout + XSD validation). **Not** used by the orchestrator's `layout` action, which does layout inline. |

<!-- markdownlint-enable MD013 -->

### `BpmnGenerationAgent` — action table

<!-- markdownlint-disable MD013 -->

| Action | Input → Output | Port | Implementation |
| --- | --- | --- | --- |
| `draft` | `(UserInput, OperationContext) → BpmnRequestDraft` | `BpmnRequestDrafter` | `LlmBpmnRequestDrafter` (`generation/`) |
| `resolve` | `BpmnRequestDraft → BpmnRequest` | `BpmnRequestResolver` | `BpmnRequestResolver` (`generation/`) |
| `assessReadiness` | `BpmnRequest → ProcessInputAssessment` | `BpmnReadinessInvoker` | `AgentPlatformBpmnReadinessInvoker` (`readiness/`) |
| `startAssessing` | `(BpmnRequest, ProcessInputAssessment) → Assessing` | (inline) | `@State` machine entry |
| `extractContract` | `(ReadyBpmnContext, OperationContext) → ValidatedProcessContract` | `ProcessContractExtractor` | `LlmProcessContractExtractor` (`contract/`) |
| `createOutline` | `(ReadyBpmnContext, ValidatedProcessContract, OperationContext) → ValidatedOutline` | `BpmnProcessGenerator` | `LlmBpmnProcessGenerator` (`generation/`) |
| `composeGraph` | `ValidatedOutline → LaidOutProcessGraph` | `BpmnProcessGenerator` | `LlmBpmnProcessGenerator` — deterministic |
| `render` | `(ReadyBpmnContext, LaidOutProcessGraph) → RenderedBpmn` | `BpmnProcessGenerator` | `LlmBpmnProcessGenerator` via `BpmnGraphRenderer` |
| `validate` | `(ReadyBpmnContext, LaidOutProcessGraph, RenderedBpmn, ValidatedProcessContract) → ValidationStage` | `BpmnRepairer` | `DefaultBpmnRepairer` (`repair/`) — validate-only initial pass + repair loop, returns `ValidationStage` branch |
| `layout` | `ValidatedBpmnXml → FinalValidatedBpmnXml` | (inline) | `BpmnLayoutPort` + `BpmnXsdValidationPort` directly; errors on XSD-invalid output |
| `align` | `(ReadyBpmnContext, ValidatedProcessContract, FinalValidatedBpmnXml, OperationContext) → BpmnAlignmentReport` | `BpmnAligner` | `LlmBpmnAligner` (`alignment/`). `FIRE_ONCE`. |
| `finish` | `(ReadyBpmnContext, FinalValidatedBpmnXml, BpmnAlignmentReport) → BpmnResult` | (inline) | Critique gate: `ALIGNMENT_FAILED` if `verdict == FAILED`; writes file otherwise. `@AchievesGoal`. `FIRE_ONCE`. |
| `reassess` | `(AwaitingClarification, BpmnClarificationAnswers) → Assessing` | (inline) | Updates request with answers, re-enters `@State` machine. `FIRE_ONCE`. |

<!-- markdownlint-enable MD013 -->

### `@State` machine (readiness / clarification loop)

| State | Action | Input → Output | What happens |
| --- | --- | --- | --- |
| `Assessing` | `assess` | `Assessing → ReadinessStage` | Branches to `Ready`, `AwaitingClarification`, or `Blocked`. `clearBlackboard = true`. |
| `AwaitingClarification` | `ask` | `AwaitingClarification → BpmnClarificationAnswers` | Pauses for typed user answers via `WaitFor.formSubmission`. |
| `Ready` | `proceed` | `Ready → ReadyBpmnContext` | Feeds existing downstream chain. |
| `Blocked` | `terminate` | `Blocked → BpmnResult` | Terminates with `NEEDS_CLARIFICATION` status. |
| (outer class) | `reassess` | `(AwaitingClarification, BpmnClarificationAnswers) → Assessing` | Updates request with answers, reassesses. `clearBlackboard = true`. |

### `@State` machine (validation / short-circuit loop)

| State | Action | Input → Output | What happens |
| --- | --- | --- | --- |
| `ValidationPassed` | `proceed` | `ValidationPassed → ValidatedBpmnXml` | Unpacks validation results and proceeds to layout. |
| `ValidationFailed` | `terminate` | `ValidationFailed → BpmnResult` | Short-circuits process and terminates with `VALIDATION_FAILED` status. |

### Prompt-contribution seam (ADR-21 Track A)

Prior to S7, a `BpmnRequestPromptContributor` port in `config/` was implemented in
`generation/` and injected into `readiness`, `contract`, and `alignment` agents. This was
speculative generality: one implementation, duplicated body, injected cross-tier only to avoid
a direct call. **ADR-21 deletes it.**

The replacement is a pure `String` extension in the `domain` kernel:

```kotlin
// domain/BpmnRequestContribution.kt
fun BpmnRequest.styleGuideContribution(): String =
    styleGuide?.let { "## Style guide\n\n$it" } ?: ""
```

Each agent that wraps a style-guide prompt calls it locally:
`PromptContributor.fixed(request.styleGuideContribution())`. No cross-tier interface, no
`generation` import in `readiness`/`contract`/`alignment`, grants byte-unchanged.

---

## 4. GOAP lifecycle and repair architecture

bpmner uses [Embabel](https://github.com/embabel/embabel-agent)'s **Goal-Oriented Action Planning**
(GOAP) planner. Each `@Agent` declares `@Action` methods; each action declares its inputs as
parameters (preconditions on the blackboard) and its output as the return type. The planner
uses cost-based A\* over the action graph.

### Three runtime signals

- **`ReplanRequestedException`** — action requests replanning (thrown by the repair loop's
  no-progress and stuck-fingerprint guards; caught at the `BpmnRepairLoop` boundary).
- **`ProcessExecutionStuckException`** — no applicable action exists for the goal.
- **`ProcessExecutionTerminatedException`** — `Budget(actions = N)` exhausted before goal reached.

Both of the latter are surfaced by `AgentProcessExecution.fromProcessStatus()`.

### Scoped readiness sub-process

The orchestrator's `assessReadiness` action delegates to `BpmnReadinessInvoker`:

```kotlin
val agent = agentPlatform.agents().find { it.name == "BpmnReadinessAgent" }
val process = agentPlatform.createAgentProcessFrom(agent, ProcessOptions(
    budget = Budget(actions = config.budget.readiness), ephemeral = true, ...
), request)
```

Binding to **only** `BpmnReadinessAgent` is load-bearing — a whole-platform plan for
`ProcessInputAssessment` would also match the orchestrator's `assessReadiness` action and
could recurse. Scoping removes that collision.

### Validate + repair loop

The `validate` action delegates to `BpmnRepairer` (`DefaultBpmnRepairer`):

```text
(graph, rendered, contract) ──► BpmnRepairAdvancer.initialEvaluation
        │
        ├── [Clean] ──────────────────────────────────────────────────────► ValidatedBpmnXml
        │
        └── [Diagnostics remain] ──► BpmnRepairLoop (RepeatUntilAcceptable sub-process)
                                         ├── LocalFixApplier
                                         ├── LlmLabelPatch
                                         ├── LlmStructuralPatch
                                         └── FullLlmRewrite
```

Repair uses cost-aware escalation (Deterministic Local → LLM Label → LLM Structural → Full
LLM Rewrite). Exits early when all blocking diagnostics are resolved, or after
`maxRepairIterations`.

### The repair / Pkl contract

Every rule declares `RepairMetadata` in its Kotlin bean config:

| Field | Type | Default | Meaning |
| --- | --- | --- | --- |
| `kind` | `RepairKind` | `LLM_MODEL_PATCH` | Repair strategy |
| `safety` | `RepairSafety` | `LLM_ONLY` | Whether repair needs operator review |
| `handler` | `String?` | `null` | For `LOCAL_MODEL_FIX`, the Spring bean name of the handler |
| `replacementMap` | `Map<String, String>?` | `null` | Optional source→replacement map |

`BpmnLocalRepairCapabilityValidator` (`repair/internal/domain/`) fails context refresh if
any `LOCAL_MODEL_FIX` rule names an unregistered handler. `AgentDeploymentValidator`
(`orchestration/internal/adapter/inbound/`, moved from `config/` in ADR-22 Track A) performs
an analogous startup check on deployed agents.

---

## 5. End-to-end pipeline

### Module map

<!-- markdownlint-disable MD013 -->

| Module | Owns | Key public types |
| --- | --- | --- |
| `api/` | Stable, annotation-free contract types (Jackson-free as of ADR-22 Decision 3). | `BpmnDefinition`, `BpmnNode`, `RuleMetadata`, `RuleCategory`, `RepairKind`. |
| `domain/` | Pure BPMN graph kernel + cross-tier DTOs. No `com.embabel.*` import. | `BpmnRequest`, `BpmnDefinition` (impl), `LaidOutProcessGraph`, `RenderedBpmn`, `BpmnElementIndex`. Also: `styleGuideContribution()` (pure `String` extension, ADR-21). |
| `config/` | Spring `@ConfigurationProperties` + pipeline configuration. `BpmnConfig` registered module-locally via `@EnableConfigurationProperties(BpmnConfig::class)` on `BpmnPipelineConfig` (ADR-22 Decision 1). | `BpmnConfig`, `BpmnPipelineConfig`, `OpenRouterModelsConfig`. |
| `orchestration/` | Single `generateBpmn` orchestrator: thin `@Action` shims + `AgentDeploymentValidator` (moved from `config/` in ADR-22). | `BpmnGenerationAgent`, `AgentDeploymentValidator`. |
| `readiness/` | Guardrail 1: LLM input assessment, ready-state handoff, scoped sub-process. | `BpmnReadinessInvoker` (port), `AgentPlatformBpmnReadinessInvoker`, `ProcessInputAssessment`, `ReadyBpmnContext`. |
| `contract/` | Guardrail 2: source-grounded process contracts. | `ProcessContractExtractor` (port), `LlmProcessContractExtractor`, `ProcessContract`, `ValidatedProcessContract`. |
| `generation/` | Request drafting, typed LLM generation, composition, XML rendering, agent invocation. | `BpmnRequestDrafter` (port), `BpmnProcessGenerator` (port), `BpmnAgentInvoker`, `AgentPlatformBpmnAgentInvoker`, `BpmnResult`. |
| `repair/` | Validation + iterative repair loop. Contract-aware validation wrapper. | `BpmnRepairer` (port), `DefaultBpmnRepairer`, `BpmnRepairLoop`, `BpmnRepairAdvancer`. |
| `validation/` | Diagnostic discovery: structural checks, XSD, in-process rule evaluation. | `BpmnValidator` (port), `BpmnLintingPort`, `BpmnXsdValidationPort`, `ValidatedBpmnXml`, `FinalValidatedBpmnXml`. |
| `layout/` | Deterministic auto-layout + final XSD validation. | `BpmnLayoutAgent`, `BpmnLayoutPort` (port), `LayoutedBpmnXml`. |
| `alignment/` | Guardrail 3: semantic comparison vs process contract. | `BpmnAligner` (port), `LlmBpmnAligner`, `BpmnAlignmentReport`. |
| `rules/` | Pkl rule catalog + rule engine. | `RuleEngine` (port), `BpmnerLintConfig`. |
| `observability/` | Process-finished summary, validation event logging, SSE progress projection. | `BpmnerRunSummaryListener`, `BpmnPipelineObserver`, `BpmnProgressProjectionObserver`. |
| `preview/` | Standalone preview artifact generator: BPMN → transient temp-dir `.preview.html` (deleted on JVM exit) with bundled local viewer. Wired into `generate` via `BpmnPreviewOrchestrator`. `allowedDependencies = []`. | `BpmnPreviewWriter` (`@SecondaryPort`), `ClasspathBpmnPreviewWriter` (`@SecondaryAdapter`). |

<!-- markdownlint-enable MD013 -->

### Pipeline diagram

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
    │  validate            repair loop → ValidationStage   (BpmnRepairer)   │
    │     ▼  (state machine: ValidationPassed → proceed → ValidatedBpmnXml; │
    │         ValidationFailed → terminate → BpmnResult)                    │
    │  layout              inline BpmnLayoutPort + XSD → FinalValidatedBpmnXml│
   │     ▼                  throws on XSD-invalid output                   │
   │  align               LLM → BpmnAlignmentReport       (BpmnAligner)    │
   │     ▼                  critique gate: no throw on verdict              │
   │  finish  @AchievesGoal(generateBpmn)  writes file → BpmnResult        │
   │            verdict==FAILED → ALIGNMENT_FAILED (no file write)         │
   └───────────────────────────────────────────────────────────────────────┘
```

### Entrypoints

Both entrypoints reach `generateBpmn` by resolving the orchestrator by name on `AgentPlatform`:

- **Shell** — `BpmnShellCommands` (`generation/internal/adapter/inbound/`) exposes `generate`
  / `gen` / `g`, seeding `UserInput` in **closed mode**.
- **Web (Tripper `JourneyController`)** — `BpmnWebController` → `WebGenerationStarter` calls
  `BpmnAgentInvoker.startAsync(request)` in `INTERACTIVE` mode; returns `202 {processId,
  sseUrl}`. No synchronous readiness pre-check. Clarification via `WaitFor.formSubmission`
  over SSE.

### Configuration

`BpmnConfig` (`@ConfigurationProperties("bpmner")`) controls the pipeline. As of ADR-22
Decision 1, it is registered **module-locally** in `config/BpmnPipelineConfig` via
`@EnableConfigurationProperties(BpmnConfig::class)` — it no longer depends on the app-root
`BpmnerApplication` scan, which enables `DIRECT_DEPENDENCIES` module tests (ADR-22 §5 S7).

For the full configuration reference see [`operator-guide.md`](./operator-guide.md).

---

## 6. Enforcement

The boundary enforcement stack (see [adr-002-module-architecture.md](./adr-002-module-architecture.md)):

- **`BpmnerModulithTest`** — `ApplicationModules.of(…, excludeBazelTestClasses).verify()`
  checks acyclicity and declared boundaries. Module tests target `DIRECT_DEPENDENCIES` for **5
  of 10** modules (`validation`, `readiness`, `contract`, `alignment`, `rules`; ADR-23 Decision
  1); `layout` and `repair` keep `ALL_DEPENDENCIES` because their required beans are two module
  hops away (§10 follow-on); `generation`, `observability`, `orchestration` keep
  `ALL_DEPENDENCIES` with documented rationale (deep transitive agent graph).
- **`BpmnerArchitectureTest`** — `ensureOnionSimple`, `ensureHexagonal(LENIENT)`, 5 bespoke
  pin rules (including the ACL pin: `RuleEngineLintingAdapter` is the sole `validation` class
  permitted to depend on `rules` `@PrimaryPort`s — ADR-23 Decision 2),
  `excludeBazelTestClasses`.
- **`BpmnerModuleBoundariesTest`** — per-module cross-`internal` rules + three `domain`
  guards: `domain does not depend on other modules except api`, `domain does not depend on
  forbidden framework prompt or io types` (`forbiddenPromptGlue` bans `com.embabel.common.ai.prompt`
  in `domain`), `domain contains only the approved kernel types` (closed `DOMAIN_ALLOWLIST`,
  enforcing the **placement-rule table** from ADR-20 §6).
- **`ApiAnnotationFreeTest`** — enforces that `api` types carry no Jackson, Jakarta, Spring,
  or Embabel annotations. As of ADR-22 Decision 3 the `RuleCategory` carve-out is removed:
  `api` is genuinely Jackson-free with no exception.
- **`src/test/resources/archunit_ignore_patterns.txt`** — Kotlin-synthetic regex suppressions
  only (`$\d+`, `$Companion`, etc.); masks no product-code dependency.

### Placement-rule table (ADR-20 §6)

A type's home is decided by what language it speaks and which slice owns its lifecycle:

| What it is | Home |
| --- | --- |
| BPMN graph types + cross-tier DTOs | `domain/` |
| Annotation-free API contracts | `api/` |
| Spring config / properties | `config/` |
| Slice-local vocabulary (rule id, repair kind, readiness enum) | owning slice's root package |
| LLM-backed adapter (port impl) | `<module>/internal/adapter/inbound/` |
| Deterministic outbound adapter | `<module>/internal/adapter/outbound/` |
| Use-case orchestration | `<module>/internal/domain/` |

---

## 7. ADR log

| ADR | Title | Status |
| --- | --- | --- |
| [ADR-001](./adr-001-single-agent-design.md) | Single-Agent Design for BPMN Generation | Accepted (epic #399, #409, 2026-06-15) |
| [ADR-002](./adr-002-module-architecture.md) | Subdomain Context Map and Rich-Graph Domain Model | Accepted (epic #424, S1, 2026-06-17) |
