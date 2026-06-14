# Hexagonal Architecture in bpmner

Every Kotlin module in `dev.groknull.bpmner.*` is shaped as a hexagon: a small set of **ports** (interfaces declared in the module's public package) and a private interior of **adapters** and **domain services** under `internal/`. The roles are made explicit with jMolecules annotations rather than left to convention, and they are enforced at build time by `BpmnerArchitectureTest`.

This document validates the structural pattern, shows where each role lives, and uses real snippets from the codebase to illustrate each role.

The data flow *through* these modules — the agent pipeline from `BpmnRequest` to `BpmnResult` — is in [`pipeline-architecture.md`](./pipeline-architecture.md). This document is about the structural shape of each module rather than what flows between them.

## The four roles

| Annotation | Role | Plain-English meaning |
| --- | --- | --- |
| `@PrimaryPort` | Inbound port (use-case API) | What this module *offers* the rest of the system. |
| `@PrimaryAdapter` | Inbound adapter | A specific way an outside party triggers the module (CLI command, Spring Shell, event listener, agent action). |
| `@SecondaryPort` | Outbound port (SPI) | What this module *needs* from the outside world to do its job. |
| `@SecondaryAdapter` | Outbound adapter | A specific implementation of an SPI, bound to a concrete technology (GraalJS, Camunda, file I/O). |

All four come from `org.jmolecules.architecture.hexagonal`. The DDD building blocks (`@Service`, `@DomainEvent`) come from `org.jmolecules.ddd.annotation` and `org.jmolecules.event.annotation`.

## Module layout

The pattern is consistent across `generation/`, `validation/`, `repair/`, `layout/`, and `observability/`:

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

A `@PrimaryPort` interface always has an implementation in `internal/domain/` that carries `@Service`. A `@SecondaryPort` interface always has an implementation in `internal/adapter/outbound/` that carries `@SecondaryAdapter`.

## Worked example: the `validation` module

`validation/` is the cleanest illustration because every role appears exactly once.

```text
validation/
├── BpmnValidator.kt            @PrimaryPort   (use-case API)
├── BpmnLintingPort.kt          @SecondaryPort (rule-evaluation SPI)
├── BpmnXsdValidationPort.kt    @SecondaryPort (XSD SPI)
├── BpmnRuleGuidancePort.kt     @SecondaryPort (rule docs SPI)
├── BpmnValidationPassedEvent   @DomainEvent
├── BpmnValidationFailedEvent   @DomainEvent
└── internal/
    ├── adapter/
    │   └── outbound/
    │       ├── RuleEngineLintingAdapter.kt @Component (delegates to rules.RuleEngine)
    │       └── BpmnXsdValidator.kt         @SecondaryAdapter (JAXP)
    └── domain/
        ├── BpmnEvaluationPipeline.kt       @Service implements BpmnValidator
        ├── BpmnDiagnosticNormalizer.kt     @Service
        ├── BpmnDefinitionValidator.kt      @Service
        └── LlmValidator.kt
```

> `BpmnLintingPort` is misleadingly named — `RuleEngineLintingAdapter` delegates to
> the Pkl rule catalog, not a JS linter. The port and types (`LintIssue`,
> `BpmnLintRuleCapability`) are kept for now so consumers don't churn; renaming
> them is tracked separately.

### Primary port — the module's API

`src/main/kotlin/dev/groknull/bpmner/validation/BpmnValidator.kt`:

```kotlin
package dev.groknull.bpmner.validation

import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
interface BpmnValidator {
    fun evaluate(
        graph: LaidOutProcessGraph,
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        renderFailureMessage: String? = null,
        repairAttempts: Int,
    ): BpmnEvaluation

    fun toRecord(attempt: BpmnRepairAttempt, repairPromptFingerprint: String? = null): BpmnAttemptRecord
    fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>)
}
```

`BpmnValidator` is the **only** thing other modules import from `validation`. The `repair` module's `BpmnRepairAdvancer` depends on `BpmnContractAwareValidator` (an internal wrapper around `BpmnValidator`), not on `BpmnEvaluationPipeline` or any other internal type.

### Secondary port — what `validation` needs

`src/main/kotlin/dev/groknull/bpmner/validation/BpmnLintingPort.kt`:

```kotlin
package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnDefinition
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface BpmnLintingPort {
    fun lint(definition: BpmnDefinition): List<LintIssue>?
    fun autoFix(bpmnXml: String, issues: List<LintIssue>): BpmnAutoFixResult?
    fun ruleDocs(ruleNames: Collection<String>): Map<String, String>
    fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability>
}
```

This declares "the validation module needs *something* that can evaluate rules against a BPMN definition." It deliberately doesn't say *how*.

### Secondary adapter — the concrete implementation

`src/main/kotlin/dev/groknull/bpmner/validation/internal/adapter/outbound/RuleEngineLintingAdapter.kt`:

```kotlin
package dev.groknull.bpmner.validation.internal.adapter.outbound

import dev.groknull.bpmner.rules.RuleEngine
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.validation.BpmnLintingPort
import org.springframework.stereotype.Component

@Component
internal class RuleEngineLintingAdapter(
    private val ruleEngine: RuleEngine,
    private val ruleRegistry: RuleRegistry,
) : BpmnLintingPort {
    // … delegates to ruleEngine.evaluate(definition) and projects RuleRegistry
    //   metadata into LintIssue / BpmnLintRuleCapability shapes
}
```

The adapter is `internal` and Spring-registered. Branch by Abstraction kept the port intact while the GraalJS-hosted `BpmnLintService` was swapped for this Kotlin-native implementation; consumers (`BpmnEvaluationPipeline`, `BpmnDiagnosticNormalizer`, etc.) didn't change. The class deliberately omits the jMolecules `@SecondaryAdapter` stereotype — calling another module's `RuleEngine` (a `@PrimaryPort`) violates the strict hexagonal layered-arch rule, and this adapter is closer to an Anti-Corruption Layer than a pure secondary adapter.

### Domain service — the use-case implementation

`src/main/kotlin/dev/groknull/bpmner/validation/internal/domain/BpmnDiagnosticNormalizer.kt`:

```kotlin
package dev.groknull.bpmner.validation.internal.domain

import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

@Service                       // jMolecules DDD marker (domain semantics)
@Component                     // Spring DI registration
internal class BpmnDiagnosticNormalizer(
    private val lintingPort: BpmnLintingPort,   // ← depends on a port, not an adapter
) {
    // … strips rule-id prefixes, stamps RepairKind on each LintIssue
}
```

Two things to note:

1. The `@Service` here is `org.jmolecules.ddd.annotation.Service`, **not** `org.springframework.stereotype.Service`. The jMolecules annotation says "this is a domain service in DDD terms"; `@Component` does the Spring DI registration.
2. The constructor depends on `BpmnLintingPort` — the SPI interface — not on `RuleEngineLintingAdapter`. Domain code never sees the concrete adapter.

## Worked example: the `generation` module

`generation/` shows the same shape with an Embabel-native primary adapter plus a public secondary port for callers that need to start agent processes directly.

### Embabel primary adapter

Shell users enter through Embabel Shell's built-in `x` / `execute` command. That places `UserInput` on the blackboard; `BpmnGenerationGateAgent` extracts a `BpmnRequestDraft`, resolves it to a `BpmnRequest`, gates readiness, and emits `ReadyBpmnContext` for the downstream pipeline.

`src/main/kotlin/dev/groknull/bpmner/generation/internal/adapter/inbound/BpmnGenerationGateAgent.kt`:

```kotlin
@Application
@Agent(description = "Resolve shell BPMN requests and gate generation on readiness")
internal class BpmnGenerationGateAgent {
    @Action
    fun draftBpmnRequest(userInput: UserInput, context: OperationContext): BpmnRequestDraft = TODO()

    @Action
    fun resolveBpmnRequest(draft: BpmnRequestDraft): BpmnRequest = TODO()

    @AchievesGoal(
        export = Export(
            name = "prepareBpmnGeneration",
            startingInputTypes = [UserInput::class, BpmnRequest::class],
        ),
    )
    @Action(pre = ["assessmentReady"])
    fun approveReadyRequest(request: BpmnRequest, assessment: ProcessInputAssessment): ReadyBpmnContext = TODO()
}
```

### Public secondary port for process starts

`src/main/kotlin/dev/groknull/bpmner/generation/BpmnAgentInvoker.kt`:

```kotlin
@SecondaryPort
interface BpmnAgentInvoker {
    fun generate(request: BpmnRequest): BpmnResult
    fun startAsync(request: BpmnRequest, assessment: ProcessInputAssessment): AgentProcess
}
```

This port is public because `web/` starts an async agent process after performing HTTP readiness checks. The concrete implementation, `AgentPlatformBpmnAgentInvoker`, lives in `generation/` and is the only adapter that drives the Embabel agent platform.

The web adapter does not duplicate shell behavior. It assesses readiness up front, returns HTTP 422 when blocked, and otherwise seeds the agent process with `BpmnRequest + ProcessInputAssessment`; `BpmnGenerationGateAgent.approveReadyRequest` converts that pair to the same `ReadyBpmnContext` used by shell starts.

## Where the pattern bends

The pattern is uniform enough to be useful, and bent in exactly the places where uniformity would have hurt.

### Secondary ports inside `internal/domain/`

```text
repair/internal/domain/BpmnRepairPorts.kt    contains:
  @SecondaryPort internal interface BpmnRepairPromptPort
  @SecondaryPort internal interface BpmnPatchApplicationPort

repair/internal/domain/BpmnRepairStrategy.kt contains:
  @SecondaryPort internal interface BpmnRepairStrategy : Ordered
```

These are SPIs the repair domain *needs*, but they are private to the module. Placing them at the module root would advertise a contract that nothing outside the module is allowed to fulfil. Keeping them in `internal/domain/` keeps the public surface honest while still using the hexagonal role markers.

### `observability/` has no `@SecondaryPort`

`observability/` only contains `@PrimaryAdapter` event listeners:

- `BpmnPipelineObserver` listens to validation events.
- `BpmnerRunSummaryListener` listens to Embabel `AgentProcessFinishedEvent`.
- `BpmnerLoggingAgenticEventListener` writes structured logs.

The module is a *one-way sink*. It receives events but never asks any other module for anything, so it has nothing to declare as an SPI.

### `core/` and `config/` use no hexagonal annotations

`core/` holds shared data classes (`BpmnDefinition`, `BpmnDiagnostic`, `RepairKind`, …) — DDD values, not bounded behaviour. `config/` holds Spring `@ConfigurationProperties` and platform configuration. Neither owns a use-case, so the hexagonal role markers don't apply.

## How the rules are enforced

`BpmnerArchitectureTest` runs as part of the standard test suite. It uses the jMolecules ArchUnit integration to fail the build if the architecture drifts:

`src/test/kotlin/dev/groknull/bpmner/BpmnerArchitectureTest.kt`:

```kotlin
class BpmnerArchitectureTest {
    private val classes =
        ClassFileImporter()
            .withImportOption(ImportOption.DoNotIncludeTests())
            .importPackages("dev.groknull.bpmner")

    @Test
    fun `verifies onion architecture`() {
        JMoleculesArchitectureRules.ensureOnionSimple().check(classes)
    }

    @Test
    fun `verifies DDD building block rules`() {
        JMoleculesDddRules.all().check(classes)
    }
}
```

`ensureOnionSimple()` checks the onion-architecture rules (a generalisation that subsumes the hexagonal layering): domain code may not depend on adapter code, adapters may not bypass ports, and so on. `JMoleculesDddRules.all()` checks the DDD building-block rules around `@Service`, `@Repository`, `@DomainEvent`, etc.

Spring Modulith adds a second layer of enforcement: `BpmnerModulithTest` verifies that the public package of each module is the only thing other modules import from it, so an inadvertent `import dev.groknull.bpmner.validation.internal.…` from outside `validation` would fail CI even if it slipped past the hexagonal check.

## When to use which annotation

A few decision points that come up often:

- **Adding a new way to trigger generation** (HTTP endpoint, schedule, message queue) — add a primary adapter for the new transport, then start an Embabel process through `BpmnAgentInvoker` or seed the same blackboard types the gate agent already consumes. Do not reintroduce bespoke shell commands or startup flags.
- **Adding a new validator** (e.g. semantic guard rails over the typed `BpmnDefinition`) — `@Service` in `validation/internal/domain/`. Implement `BpmnValidator` or be invoked by `BpmnEvaluationPipeline`.
- **Swapping the rule engine for a different evaluator** — write a new `@SecondaryAdapter` that implements `BpmnLintingPort`. Toggle via Spring profile or `@Primary`.
- **Adding a brand-new module** (say, an export module) — create `export/` with `BpmnExportUseCase` (`@PrimaryPort`), `BpmnExportTargetPort` (`@SecondaryPort`), an `internal/domain/` service and `internal/adapter/{inbound,outbound}/` adapters. The architecture and Modulith tests pick up the new module without configuration.
- **Cross-module event** (something one module emits, others react to) — `@DomainEvent` in the emitting module's public package. Listeners are `@PrimaryAdapter` in the consuming module.

If a new piece of code doesn't fit any of these roles, that usually means it belongs in `core/` (data) or `config/` (Spring infrastructure), not in a module.

## Reading the codebase faster

A few rules of thumb that hold across the codebase:

- **A file in `<module>/` root is part of the public API.** It is either a `@PrimaryPort`, a `@SecondaryPort`, or a `@DomainEvent`.
- **A file in `<module>/internal/domain/` does the work.** It carries `@Service` (jMolecules) and depends on ports, never adapters.
- **A file in `<module>/internal/adapter/inbound/` is a way *in*.** It carries `@PrimaryAdapter` and depends on a `@PrimaryPort`.
- **A file in `<module>/internal/adapter/outbound/` is a way *out*.** It carries `@SecondaryAdapter` and implements a `@SecondaryPort`.

When in doubt, grep for the annotation: every Kotlin file using a hexagonal role declares its role with one of these four annotations at the top of the class.
