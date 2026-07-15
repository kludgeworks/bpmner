# bpmner — Architecture

> **Status:** Authoritative architecture document. Legacy docs (`agents.md`,
> `goap-lifecycle.md`, `pipeline-architecture.md`, `hexagonal-architecture.md`) folded in
> and deleted. Module vocabulary reflects post-#451 renames. ADR index in §7.

---

## 1. Context map

Three bounded contexts, one application layer, delivery adapters, and cross-cutting
infrastructure. See §2 for the authoritative module map and [ADR-004](./adr/adr-004-module-placement-and-boundaries.md) for the placement rule.

<!-- markdownlint-disable MD013 -->

| Context / Layer | Role | Owns | Current modules (as-built) |
| --- | --- | --- | --- |
| **Authoring** | Core domain | The BPMN process graph as a behaviour-bearing domain object; its structural invariants; generation / contract drafting | `bpmn`, `authoring`, `contract`, `layout` |
| **Conformance** | Supporting domain | Rule catalogue + evaluation + repair; own ubiquitous language (rule id / severity / capability, Pkl-fed) | `ruleset`, `conformance`, `repair`, `alignment` |
| **Intake / Readiness** | Supporting domain | Request readiness + clarification subdomain | `readiness` |
| **Generation Orchestration** | Application layer — not a domain context | Single `BpmnGenerationAgent`, GOAP wiring, `@Action` shims; HTTP + shell inbound adapters | `pipeline` |
| **Delivery adapters** | Inbound/primary adapters — not contexts | HTTP + shell dissolved into `pipeline` in epic #451 S5 | *(see `pipeline` above)* |
| **Preview** | Output artifact generator — not a domain context | BPMN → transient temp-dir HTML preview artifact; bundled local viewer assets | `preview` |
| **Cross-cutting** | Infrastructure / sink — not contexts | LLM provider registration; event sink; preview | `llm`, `telemetry`, `browser`, `preview` |

<!-- markdownlint-enable MD013 -->

Key decisions:

- `bpmn` is the **BPMN language kernel** — annotation-free types and rule SPI at the root;
  Jackson-bound implementations in `bpmn/internal/model/`. No framework annotations in the root.
- The BPMN process graph is a **behaviour-bearing domain object** — model-intrinsic
  invariants (`validateStructure()`, `validateOwnership()`) live on graph types in `bpmn`.
  No aggregate/repository/persistence machinery.
- "Authoring / Conformance / Intake" are **subdomain labels** (grouping lenses),
  not separate directory structures.

### Module boundaries

Explicit `allowedDependencies` declarations were removed from all capability modules in
epic #536. Boundaries are enforced by `BpmnerArchitectureTest` and `BpmnerModulithTest`;
the `bpmn` kernel retains `allowedDependencies = []`.

---

## 2. Module shape, ports, and Onion layering

Every Kotlin module in `dev.groknull.bpmner.*` has a small set of **ports** (interfaces
declared in the module's public package) and a private interior of **adapters** and
**domain services** under `internal/`. Simplified jMolecules Onion annotations make the
roles explicit and `BpmnerArchitectureTest` enforces them at build time.

### Onion roles

| Annotation | Role | Plain-English meaning |
| --- | --- | --- |
| `@DomainRing` | Domain model and logic | Pure BPMN rules, values, and domain services. |
| `@ApplicationRing` | Public capability API and use-case service | What this module offers, plus application services and existing ports it uses for in-process collaboration. |
| `@InfrastructureRing` | Technical adapter | Spring, CLI, web, agent-platform, OS-command, XML, or other external-system integration. |

All three come from `org.jmolecules.architecture.onion.simplified`. The DDD building
blocks (`@Service`, `@DomainEvent`) come from `org.jmolecules.ddd.annotation` and
`org.jmolecules.event.annotation`.

### Module layout pattern

```text
<module>/
├── <CapabilityApi>.kt        # @ApplicationRing interfaces (use-cases)
├── <CapabilityPort>.kt       # @ApplicationRing interfaces (existing ports)
├── <DomainEvent>.kt          # @DomainEvent classes (cross-module events)
└── internal/
    ├── adapter/
    │   ├── inbound/          # @InfrastructureRing classes (triggers)
    │   └── outbound/         # @InfrastructureRing classes (integrations)
    └── domain/               # @Service (DDD) classes (use-case implementations)
```

| Component | Default location | Required annotations | Spring stereotype |
| --- | --- | --- | --- |
| Public capability API | `<module>/` (public package) | `@ApplicationRing` | (none — it's an interface) |
| Existing port / internal use-case contract | `<module>/` *or* `<module>/internal/domain/` | `@ApplicationRing` | (none — it's an interface) |
| Domain Service | `<module>/internal/domain/` | `@Service` (jMolecules DDD) | `@Component` |
| Inbound Adapter | `<module>/internal/adapter/inbound/` | `@InfrastructureRing` | `@Component` (or `@ShellComponent`, `@Agent`, etc.) |
| Outbound Adapter | `<module>/internal/adapter/outbound/` | `@InfrastructureRing` | `@Component` or `@Service` |

The `bpmn/` kernel uses no hexagonal annotations — the root holds the annotation-free BPMN
language layer + rule SPI; `bpmn/internal/model/` holds the Jackson-bound implementations.
Per-capability `@ConfigurationProperties` classes live in each capability's public package.

### Where the pattern bends

- **Existing ports inside `internal/domain/`** — `repair/internal/domain/BpmnRepairPorts.kt`
  holds `@ApplicationRing internal interface BpmnRepairPromptPort` and `BpmnPatchApplicationPort`.
  These are private to the module; placing them at root would advertise a contract nothing
  outside the module is allowed to fulfil.
- **`telemetry/` has no outbound port** — it is a one-way sink: event listeners only,
  no SPI.

### When to use which annotation

- **Adding a new way to trigger generation** — add an infrastructure-ring adapter for the new transport,
  start an Embabel process through `BpmnAgentInvoker` or seed the same blackboard types.
- **Adding a new validator** — `@Service` in `conformance/internal/domain/`.
- **Adding a technical rule engine** — write an `@InfrastructureRing` implementation of
  `BpmnLintingPort`. The in-process `ruleset` collaboration is instead the
  `@ApplicationRing` `RuleEngineLintingService`.
- **Adding a brand-new module** — create `<module>/` with its `@ApplicationRing` API and ports,
  `internal/domain/`, and `internal/adapter/{inbound,outbound}/` skeleton; the architecture
  and Modulith tests pick it up without configuration.
- **Cross-module event** — `@DomainEvent` in the emitting module's public package; listeners
  are `@InfrastructureRing` adapters in consuming modules.

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
| `BpmnGenerationAgent` | `pipeline/internal/adapter/inbound/BpmnGenerationAgent.kt` | 15 typed shims: `draft`, `resolve`, `assessReadiness`, `startAssessing`, `extractContract`, `createOutline`, `composeGraph`, `render`, `validate`, `layout`, `align`, `finish`, `reassess`, `proceed`, `terminate` | `generateBpmn` (on `finish`, `terminate` or `Blocked.terminate`) | The single orchestrator. Each action delegates to a public port; `finish` writes the output file and returns `BpmnResult`. |
| `BpmnReadinessAgent` | `readiness/internal/adapter/inbound/BpmnReadinessAgent.kt` | 1: `assessReadiness` (`BpmnRequest → ProcessInputAssessment`) | `assessReadiness` | Invoked as a **scoped sub-process** by the orchestrator's `assessReadiness` action, not chained into the main plan. Style-guide prompt contribution is applied locally via `PromptContributor.fixed(request.styleGuideContribution())` (ADR-005 Track A). |
| `BpmnLayoutAgent` | `layout/internal/adapter/inbound/BpmnLayoutAgent.kt` | 2: `layoutBpmnXml`, `validateFinalBpmnXml` | `finalizeLayout` | Standalone layout agent (GraalJS auto-layout + XSD validation). **Not** used by the orchestrator's `layout` action, which does layout inline. |

<!-- markdownlint-enable MD013 -->

### `BpmnGenerationAgent` — action table

<!-- markdownlint-disable MD013 -->

| Action | Input → Output | Port | Implementation |
| --- | --- | --- | --- |
| `draft` | `(UserInput, OperationContext) → BpmnRequestDraft` | `BpmnRequestDrafter` | `LlmBpmnRequestDrafter` (`authoring/`) |
| `resolve` | `BpmnRequestDraft → BpmnRequest` | `BpmnRequestResolver` | `BpmnRequestResolver` (`authoring/`) |
| `assessReadiness` | `BpmnRequest → ProcessInputAssessment` | `BpmnReadinessInvoker` | `AgentPlatformBpmnReadinessInvoker` (`readiness/`) |
| `startAssessing` | `(BpmnRequest, ProcessInputAssessment) → Assessing` | (inline) | `@State` machine entry |
| `extractContract` | `(ReadyBpmnContext, OperationContext) → ValidatedProcessContract` | `ProcessContractExtractor` | `LlmProcessContractExtractor` (`contract/`) |
| `createOutline` | `(ReadyBpmnContext, ValidatedProcessContract, OperationContext) → ValidatedOutline` | `BpmnProcessGenerator` | `LlmBpmnProcessGenerator` (`authoring/`) |
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

### Prompt-contribution seam (ADR-005 Track A)

`BpmnRequest.styleGuideContribution(): String` lives as a top-level extension in the `bpmn`
kernel (`bpmn/BpmnRequestContribution.kt`). Each call site wraps it locally with
`PromptContributor.fixed(request.styleGuideContribution())` — no cross-tier interface,
no stub required in module tests. See [ADR-005](./adr/adr-005-prompt-contribution-seam.md).

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

Both of the latter are surfaced by `AgentProcessExecution.fromProcessStatus()`. For how to diagnose each signal at runtime, see the [Troubleshooting section](./operator-guide.md#processexecutionstuckexception) of the operator guide.

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
(`pipeline/internal/adapter/inbound/`) performs an analogous startup check on deployed agents.

---

## 5. End-to-end pipeline

### Module map

<!-- markdownlint-disable MD013 -->

| Module | Owns | Key public types |
| --- | --- | --- |
| `bpmn/` | BPMN language kernel. Root: annotation-free graph interfaces + rule SPI. `bpmn/internal/model/`: Jackson-bound concrete implementations. No Spring or Embabel imports in root. | `BpmnDefinition`, `BpmnNode`, `BpmnRequest`, `BpmnRule`, `RuleMetadata`, `RuleCategory`, `RepairKind`, `LaidOutProcessGraph`, `RenderedBpmn`. Also: `styleGuideContribution()` (ADR-005 Decision 1). |
| `authoring/` | Request drafting, typed LLM generation, composition, XML rendering, agent invocation. | `BpmnRequestDrafter` (port), `BpmnProcessGenerator` (port), `BpmnAgentInvoker`, `AgentPlatformBpmnAgentInvoker`, `BpmnResult`. |
| `conformance/` | Diagnostic discovery: structural checks, XSD, in-process rule evaluation. | `BpmnLintingPort`, `BpmnXsdValidationPort`, `ValidatedBpmnXml`, `FinalValidatedBpmnXml`. |
| `ruleset/` | Modelling-rule catalogue + rule engine. | `RuleEngine` (port), `RuleRegistry` (port), `BpmnerLintConfig`. |
| `contract/` | Guardrail 2: source-grounded process contracts. | `ProcessContractExtractor` (port), `LlmProcessContractExtractor`, `ProcessContract`, `ValidatedProcessContract`. |
| `readiness/` | Guardrail 1: LLM input assessment, ready-state handoff, scoped sub-process. | `BpmnReadinessInvoker` (port), `AgentPlatformBpmnReadinessInvoker`, `ProcessInputAssessment`, `ReadyBpmnContext`. |
| `alignment/` | Guardrail 3: semantic comparison vs process contract. | `BpmnAligner` (port), `LlmBpmnAligner`, `BpmnAlignmentReport`. |
| `repair/` | Validation + iterative repair loop. | `BpmnRepairer` (port), `DefaultBpmnRepairer`, `BpmnRepairLoop`, `BpmnRepairAdvancer`. |
| `layout/` | Deterministic auto-layout + final XSD validation. | `BpmnLayoutAgent`, `BpmnLayoutPort` (port), `LayoutedBpmnXml`. |
| `pipeline/` | Single `generateBpmn` orchestrator: thin `@Action` shims, `AgentDeploymentValidator`, HTTP and shell inbound adapters. | `BpmnGenerationAgent`, `AgentDeploymentValidator`, `BpmnWebController`, `BpmnShellCommands`. |
| `telemetry/` | Event sink: process-finished summary, validation event logging, SSE progress projection. | `BpmnerRunSummaryListener`, `BpmnPipelineObserver`, `BpmnProgressProjectionObserver`. |
| `llm/` | LLM provider registration (DeepSeek, OpenRouter). Platform-level; `allowedDependencies = []`. | Provider `@Configuration` classes. |
| `browser/` | OS-level browser launch for post-generation preview. | `BrowserOpenPort` (port). |
| `preview/` | BPMN → transient temp-dir `.preview.html` artifact. | `BpmnPreviewWriter` (`@ApplicationRing`), `ClasspathBpmnPreviewWriter` (`@InfrastructureRing`). |

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
   │                  BpmnGenerationAgent  (pipeline/)                    │
   │                                                                       │
   │  draft               LLM → BpmnRequestDraft                          │
   │     ▼                                                                 │
   │  resolve             → BpmnRequest                                   │
   │     ▼                                                                 │
   │  assessReadiness     scoped sub-process → ProcessInputAssessment      │
   │     ▼                                                                 │
   │  startAssessing      → Assessing  (@State machine entry)              │
   │     ▼  (state machine: Ready → ReadyBpmnContext; AwaitingClarification│
   │         → WaitFor.formSubmission over SSE; Blocked → NEEDS_CLARIF.)  │
   │  extractContract     LLM → ValidatedProcessContract                   │
   │     ▼                                                                 │
   │  createOutline       LLM → ValidatedOutline                           │
   │     ▼                                                                 │
   │  composeGraph        deterministic → LaidOutProcessGraph              │
   │     ▼                                                                 │
   │  render              → RenderedBpmn                                   │
   │     ▼                  emits BpmnGeneratedEvent                       │
    │  validate            repair loop → ValidationStage                   │
    │     ▼  (state machine: ValidationPassed → proceed → ValidatedBpmnXml; │
    │         ValidationFailed → terminate → BpmnResult)                    │
    │  layout              inline BpmnLayoutPort + XSD → FinalValidatedBpmnXml│
   │     ▼                  throws on XSD-invalid output                   │
   │  align               LLM → BpmnAlignmentReport                       │
   │     ▼                  critique gate: no throw on verdict              │
   │  finish  @AchievesGoal(generateBpmn)  writes file → BpmnResult        │
   │            verdict==FAILED → ALIGNMENT_FAILED (no file write)         │
   └───────────────────────────────────────────────────────────────────────┘
```

### Entrypoints

Both entrypoints reach `generateBpmn` by resolving the orchestrator by name on `AgentPlatform`:

- **Shell** — `BpmnShellCommands` (`pipeline/internal/adapter/inbound/`) exposes `generate`
  / `gen` / `g`, seeding `UserInput` in **closed mode**.
- **Web (Tripper `JourneyController`)** — `BpmnWebController` → `WebGenerationStarter` calls
  `BpmnAgentInvoker.startAsync(request)` in `INTERACTIVE` mode; returns `202 {processId,
  sseUrl}`. No synchronous readiness pre-check. Clarification via `WaitFor.formSubmission`
  over SSE.

### Configuration

Each capability module owns its `@ConfigurationProperties` binding
(config module dissolved in epic #451 S4; per ADR-004, config types belong at the capability root package). For the full configuration reference see
[`operator-guide.md`](./operator-guide.md).

### SSE wire contract {#wire-contract}

The web client receives typed SSE events over the Embabel platform channel
(`/api/bpmn/generations/events/process/{id}`). The following rules are **binding** for
all new event types — do not rename a class or property without updating the TypeScript
client in the same PR.

- **Type discriminator:** the `type` field in the SSE JSON payload is the **Kotlin simple
  class name** of the event. New event types must be concrete `class` (not `data class`)
  declarations in the telemetry module root (`dev.groknull.bpmner.telemetry`) extending
  `AbstractAgentProcessEvent`.
- **Class and property names are the API.** Renaming a Kotlin class or property changes
  the JSON field name and silently breaks the client.
- **Note on inherited properties:** `AbstractAgentProcessEvent` exposes a `status` getter
  returning `AgentProcessStatusReport`. New event types that carry a string status should
  name the property `stageStatus` (or another non-conflicting name) to avoid hiding the
  inherited getter with an incompatible type.
- **Stage keys** (for `BpmnStageEvent`): `readiness | contract | generate | validate |
  layout | align`.
- **Status values** (for `BpmnStageEvent.stageStatus`): `active | done | warn`.

The drift guard in `BpmnProgressProjectionObserverTest` enforces at build time that every
key in `ACTION_LABELS` and `ACTION_STAGES` is a live `@Action` method name — stale keys
fail the build.

---

## 6. Enforcement

The boundary enforcement stack (see [ADR-004](./adr/adr-004-module-placement-and-boundaries.md) §6):

- **`BpmnerModulithTest`** — `ApplicationModules.of(…, excludeBazelTestClasses).verify()`
  checks acyclicity and declared boundaries. Module tests target `DIRECT_DEPENDENCIES` for **6
  of 10** modules (`conformance`, `readiness`, `contract`, `alignment`, `ruleset`, `layout`;
  ADR-007 Decision 1 + epic #451 S7); `authoring`, `pipeline`, `repair`, `telemetry` keep
  `ALL_DEPENDENCIES` with documented rationale (deep transitive agent/event graph).
- **`BpmnerArchitectureTest`** — `ensureOnionSimple`, 4 bespoke pin rules, and
  `excludeBazelTestClasses`. `RuleEngineLintingService` is a permitted lateral
  application-ring collaboration with the `ruleset` APIs; no ACL-specific pin is needed.
- **`BpmnerArchitectureTest` (kernel gate)** — `bpmn kernel is free of framework, IO, and
  cross-module dependencies`: the `bpmn/` kernel module may not import other `bpmner` modules
  or framework/prompt-construction glue (ported from deleted `BpmnerModuleBoundariesTest` in
  epic #539; enforces the **placement-rule table** from ADR-004 §6).
- **`src/test/resources/archunit_ignore_patterns.txt`** — Kotlin-synthetic regex suppressions
  only (`$\d+`, `$Companion`, etc.); masks no product-code dependency.

### Placement-rule table (ADR-004 §6)

A type's home is decided by what language it speaks and which slice owns its lifecycle:

| What it is | Home |
| --- | --- |
| Annotation-free BPMN language types + rule SPI | `bpmn/` root |
| Jackson-bound BPMN model implementations | `bpmn/internal/model/` |
| Capability `@ConfigurationProperties` | capability root package |
| Slice-local vocabulary (rule id, repair kind, readiness enum) | owning slice's root package |
| LLM-backed adapter (port impl) | `<module>/internal/adapter/inbound/` |
| Deterministic outbound adapter | `<module>/internal/adapter/outbound/` |
| Use-case orchestration | `<module>/internal/domain/` |

---

## 7. ADR log

<!-- markdownlint-disable MD013 -->

| ADR | Title | Status |
| --- | --- | --- |
| [ADR-001](./adr/adr-001-single-agent-design.md) | Single-Agent Design for BPMN Generation | Accepted (epic #399, #409, 2026-06-15) |
| [ADR-002](./adr/adr-002-retryable-generation-exception.md) | Retryable generation exception: kernel placement & feedback contract | Accepted — current on `main` |
| [ADR-003](./adr/adr-003-interactive-web-generation.md) | Interactive web generation (no synchronous 422) | Accepted — current on `main` |
| [ADR-004](./adr/adr-004-module-placement-and-boundaries.md) | Module placement rule & boundaries | Accepted — current on `main` |
| [ADR-005](./adr/adr-005-prompt-contribution-seam.md) | Prompt contribution lives in the `bpmn` kernel | Accepted — current on `main` |
| [ADR-006](./adr/adr-006-agent-platform-and-module-bootstrap.md) | Agent platform wiring & module-test bootstrap | Accepted — current on `main` |
| [ADR-007](./adr/adr-007-conformance-ruleset-acl.md) | The sanctioned `conformance→ruleset` ACL | Superseded by simplified Onion stage 540-3 |
| [ADR-008](./adr/adr-008-rule-docs-golden-source.md) | Rule-docs golden source is the live bean catalog | Accepted — current on `main` |
| [ADR-009](./adr/adr-009-module-config-and-isolation.md) | Capability-owned config & module isolation | Accepted — current on `main` |
| [ADR-010](./adr/adr-010-sanctioned-architecture-exceptions.md) | Sanctioned architecture-test exceptions via opt-in marker | Accepted — current on `main` |

<!-- markdownlint-enable MD013 -->
