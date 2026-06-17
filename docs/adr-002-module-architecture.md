# ADR-002: Subdomain Context Map and Rich-Graph Domain Model

## Status

Accepted (epic #424, finalised by sub-issue S1, 2026-06-17)

## Context

### As-built state (verified against `bpmner` `main` @ `79b593b`, 2026-06-15)

`bpmner` has 14 `@ApplicationModule`s under `dev.groknull.bpmner.*`. Their structure was
verified at the commit basis used throughout this epic:

- **`core/` is a god-module**: `BpmnDomain.kt` (~843 lines of anemic `data class`es),
  `Bpmn{Graph,Pipeline,Guardrail}Types.kt`, `BpmnConfig.kt` (~271 lines) — the entire
  domain model. 13 of 14 modules list `core` in `allowedDependencies` (`api` is the sole
  exception). This is the "all dependants, whole model" shape the DDD consensus recognises
  as the God-Module anti-pattern, not a legitimate Shared Kernel (a shared kernel is a
  *small, stable* subset — a `Money` value type, a `Result` type). See challenge E1 in
  `plans/424/BLOCKER-S1.md` (upheld in the architecture refresh, see [ARCHITECTURE.md §0.B]).

- **`BpmnDefinition` / the process graph is anemic**: the central domain type is a pure
  `data class` with 13 properties and zero behaviour methods. All validate, repair, align,
  and layout logic lives in *external* service modules. This is against Embabel §6.1 (see
  D-model below).

- **Framework-in-`internal/domain`**: 16 of 27 `internal/domain` files import the Spring
  framework; **3 are deep couplings** beyond `@Component`/`@Service` — `BpmnLocalRepairCapabilityValidator`
  (`@EventListener` + `ContextRefreshedEvent`), `PklRuleCatalog` (`@Value`),
  `RuleProfileFactory` (`@Configuration` + `@Bean` + `PathMatchingResourcePatternResolver`).
  All 3 are in `rules/` or `repair/`.

- **Cross-module `internal` reaches**: prod = **0** (clean). Test = **14 imports across
  5 files**: `validation→rules ×9` (all in `BpmnerParityTest.kt`), `prompt→repair ×2`,
  `orchestration/prompt/prompts→contract ×1 each`.

- **Modules are stage-shaped, not subdomain-shaped**: `validation`, `repair`, `alignment`
  are pipeline stages, not subdomains with their own ubiquitous language. The challenge in
  `BLOCKER-S1.md` (E4) and the architecture refresh (§0.B) established that the stage
  grouping does not constitute a valid context map.

- **`web` is 3 files / 104 lines of pure HTTP↔port glue**: `BpmnWebController`
  (`@RestController`), `WebGenerationStarter` (`@Service`), `WebModule`. It is a delivery
  adapter, not a bounded context. Challenge E2 upheld.

- **`allowedDependencies`**: `api[]`, `core[api]`, `config[core]`, `readiness[core]`,
  `rules[api,core,pkl]`, `contract[api,core,readiness]`, `validation[api,core,rules]`,
  `generation[alignment,api,contract,core,readiness,validation]`,
  `repair[api,contract,core,generation,readiness,rules,validation]`,
  `layout[api,core,repair,validation]`,
  `alignment[api,contract,core,readiness,validation]`,
  `web[api,core,generation,readiness]`,
  `observability[alignment,api,core,generation,readiness,repair,validation]`,
  `orchestration[alignment,api,contract,core,generation,layout,readiness,repair,validation]`.

- **Enforcement stack**: `BpmnerModulithTest` (`ApplicationModules.of(…, excludeBazelTestClasses).verify()`);
  `BpmnerArchitectureTest` (`ensureOnionSimple`, `ensureHexagonal(LENIENT)` + 2 synthetic-class
  `ignoreDependency` + 4 bespoke pin rules: GraalJS→`..layout..`, OpenNLP→`..rules.internal.domain.nlp..`,
  `Ai`-bean→`..inbound..`, `@Agent`→`@AchievesGoal`). Carve-out file
  `src/test/resources/archunit_ignore_patterns.txt`.

- **Module tests**: `validation/ValidationModuleTest` = only live `@ApplicationModuleTest`
  (`ALL_DEPENDENCIES`, `verifyAutomatically=false`); `generation/GenerationModuleTest` =
  `@Disabled` stub; 12 of 14 modules have none; 8× `@SpringBootTest` in the test suite.

### What drove the refresh

The planner's initial S1 pass (`plans/424/BLOCKER-S1.md`) challenged the prior architecture
doc's "minimised shared kernel" recommendation on three grounds:

- **E1**: keeping `core` as the whole-model god-module-renamed-kernel does not change the
  coupling topology and is the anti-pattern, not its solution.
- **E2**: `web` is a delivery mechanism / driving adapter, not a bounded context.
- **E3**: `BpmnDefinition` is anemic; Embabel's own framework guidance requires rich
  domain objects with behaviour (§6.1).

All three challenges were upheld by the architect against both code and the Embabel/DICE
framework corpus ([ARCHITECTURE.md §0.B]). The architecture doc was rewritten (ADR-1 in
[ARCHITECTURE.md §7]) and this ADR records the resulting decisions.

## Decision

### D-map — Subdomain-shaped context map

The bounded contexts are **subdomain-shaped**, not pipeline-stage-shaped. The following
map supersedes any prior stage-grouping. It is concrete enough to fix the S2 gate targets
and the S3 relocation target without ambiguity. ([ARCHITECTURE.md §4, ADR-1(a)])

| Context | Role | Owns | Maps from today's modules |
| --- | --- | --- | --- |
| **Authoring** | Core domain (the heart) | The BPMN process graph as a behaviour-bearing domain object; its structural invariants; generation / contract drafting | `core` model + `generation` + `contract` + `layout` (`layout` is a property of the graph, not a separate subdomain) |
| **Conformance** | Supporting domain | Rule catalogue + evaluation + repair; its own ubiquitous language (`rule id` / `severity` / `capability`, Pkl-fed) | `rules` + `validation` + `repair` + `alignment` |
| **Intake / Readiness** | Supporting domain | Request readiness + clarification subdomain | `readiness` (+ `@State` clarification-machine domain types) |
| **Generation Orchestration** | Application layer — **not a domain context** | Single `BpmnGenerationAgent`, GOAP wiring, `@Action` shims | `orchestration` |
| **Delivery adapters** | Inbound/primary adapters — **not contexts** | HTTP, shell entrypoint | `web` + shell entrypoint |
| **Cross-cutting** | Infrastructure / sink — **not contexts** | Config, observability | `config`, `observability` |

Rationale for each classification:

- **Authoring as the heart**: `BpmnDefinition` / `LaidOutProcessGraph` (which already has
  `validateOwnership()`) are graph-domain concepts. Generation, contract, and layout are all
  properties *of* the graph being authored. Making them a single context with the graph type
  is the subdomain decomposition; keeping them as separate stage modules is the stage
  decomposition this ADR replaces. ([ARCHITECTURE.md §4])

- **Conformance as a genuine separate subdomain**: the `validation`–`rules`–`repair`–`alignment`
  web has its own vocabulary (rule id, severity, capability, repair kind) distinct from the
  graph's topology vocabulary. The `validation→rules ×9` cross-module test reaches are
  *intra*-context coupling that confirms these belong together, not a problem to be isolated
  away. ([ARCHITECTURE.md §4])

- **`web` / shell are adapters, not contexts** (E2 upheld, [ARCHITECTURE.md §0.B E2]):
  a REST controller is a driving/primary adapter inside the context it serves. 104 lines of
  glue do not constitute a bounded context.

- **`config` / `observability` are infrastructure/sink** (not modelling any business
  subdomain). They remain as cross-cutting modules; they are not candidates for merger or
  context classification.

- **`orchestration` is the application layer**: it threads the contexts by type via the
  GOAP planner. It owns no domain model of its own and stays in its own module as the
  application-layer coordinator. ([ARCHITECTURE.md §4, N1])

### D-model — Rich domain object; `core` slimmed

The BPMN process graph (`BpmnDefinition`, `LaidOutProcessGraph`, and related graph types)
becomes a **behaviour-bearing domain object**. Model-intrinsic logic — structural
validity checks, ownership invariants, reference-resolution checks that are properties of
the graph — moves onto the graph types. `LaidOutProcessGraph.validateOwnership()` is the
existing seed of this pattern. ([ARCHITECTURE.md §4, §0.C, ADR-1(b)])

**Framework authority**: Embabel reference §6.1 (*"A rich domain model helps build a good
agentic system. Domain objects should not merely contain state, but also expose behavior.
Avoid the anemic domain model."*); Embabel "Domain Modeling" (*"pre and post conditions…
expressed in terms of the data flow of domain objects… domain objects can have behavior
as well as state"*); DICE / Rod Johnson (*"Domain objects are not mere structs… Business
logic stays within domain objects where it belongs"*). These are framework-author-normative
citations, not generic DDD opinion.

**What "rich domain object" means here** (important scoping, [ARCHITECTURE.md §0.C]):

- It means **behaviour on the graph type**: `validate()`-style structural checks,
  ownership checks, invariants the graph can answer for itself.
- It does **not** mean Aggregate-Root + Repository + transactional-consistency ceremony.
  The pipeline is stateless and transactionless; there is no datastore. No persistence
  machinery is introduced. ([ARCHITECTURE.md N5])
- LLM-backed orchestration behaviour (generation, repair, alignment) stays in the modules
  that own it. Only **model-intrinsic** logic relocates to the graph type (S3 scope).

**`core` slimming target** (S3 physical work):

`core/` shrinks to a **minimal published vocabulary**: value types and shared vocabulary
that are genuinely shared across contexts and stable — enums, ids, `SourceEvidence`-style
records. The process-graph model and its behaviour move into the **Authoring** context.
The guardrail / readiness enums move toward **Intake/Conformance** as appropriate.
`api` is untouched; it is the published external contract and stays annotation-free
(`ApiAnnotationFreeTest` green). ([ARCHITECTURE.md §4, N4])

The physical relocation is **scheduled in epic #424** (S3 is the epic's centre of gravity),
not deferred to a follow-on. ([ARCHITECTURE.md §4 "Physical re-org scope", ADR-1 consequence])

### D-policy — Framework-in-`internal/domain` policy

**Permitted**: `@Component`-on-`@Service` in `internal/domain/` is the documented project
idiom for domain services and remains permitted. 16 of 27 `internal/domain` files use
only this form.

**Out-of-policy** (to be relocated in S4): the 3 deep couplings that go beyond this idiom:

| Class | Module | Coupling | Target (S4) |
| --- | --- | --- | --- |
| `BpmnLocalRepairCapabilityValidator` | `repair` | `@EventListener` + `ContextRefreshedEvent` | Relocate to `internal/adapter/inbound/` or a module `@Configuration` |
| `PklRuleCatalog` | `rules` | `@Value` | Relocate to `internal/adapter/` or extract config-injection point |
| `RuleProfileFactory` | `rules` | `@Configuration` + `@Bean` + `PathMatchingResourcePatternResolver` | Relocate to `internal/adapter/outbound/` or a module `@Configuration` |

S4 must verify against #420 (rules-as-Kotlin) follow-on PRs before touching `rules/` and
`repair/`. ([ARCHITECTURE.md §5 S4, §0.A, risk 2])

### D-enforce — Enforcement-stack keep / slim decision

This decision makes S2's gate strictness and S7's carve-out reconciliation unambiguous.
([ARCHITECTURE.md §1 G4/G6, §5 S2/S7])

**Keep as-is through S2:**

- `ApplicationModules.of(…, excludeBazelTestClasses).verify()` in `BpmnerModulithTest` —
  the `excludeBazelTestClasses` predicate is load-bearing (Bazel test-class paths slip past
  ArchUnit's built-in `DoNotIncludeTests`). Keep until S7.
- The `archunit_ignore_patterns.txt` Kotlin-synthetic suppressions — these suppress
  violations against compiler-generated synthetic classes that `ignoreDependency` cannot
  reach inside Modulith's internal ArchUnit invocation. Keep until S7.
- The 4 bespoke pins in `BpmnerArchitectureTest` (GraalJS→layout, OpenNLP→rules-nlp,
  Ai-bean→inbound, `@Agent`→`@AchievesGoal`) — all are semantically justified. Review
  in S7 but do not remove blindly.

**To be removed / re-justified (slated for S7):**

- The `RuleEngineLintingAdapter` `@SecondaryAdapter` omission (carve-out): once S4
  relocates the 3 deep couplings, re-evaluate whether this omission remains necessary or
  can be removed. ([ARCHITECTURE.md §1 G6])
- Each remaining `archunit_ignore_patterns.txt` entry and each bespoke pin should be
  annotated with its rationale in S7; entries that no longer apply after S3/S4/S5 changes
  are removed.

**New rules (S2 scope — not this stage):**

- S2 will add an ArchUnit rule failing on any cross-module `..<m>.internal..` import
  (prod-scoped first; test-scope extended in S5 after the 14 test reaches are fixed).
- S2 will add a framework-purity rule scoped to the 3 deep couplings (green before S4
  removes them; tightened to full `internal/domain` scope after S4).

These are recorded here to make S2's target concrete; they are not implemented in S1.
([ARCHITECTURE.md §5 S2, §1 G4])

## Consequences

- **N6 freezes this map**: downstream stages (S2–S7) cite this ADR; they do not
  re-decide the context map. Divergence returns here as an ADR note (N7, append-only).
  ([ARCHITECTURE.md N6, N7])

- **S2 gate target is now unambiguous**: the cross-module `internal` rule (prod-scope
  first, then test after S5 fixes) and the framework-purity rule (scoped to the 3 deep
  couplings) have named classes, named carve-outs, and a named sequencing.

- **S3 relocation target is now unambiguous**: model-intrinsic behaviour moves onto the
  graph types in the Authoring context; `core` slims to the minimal published vocabulary;
  `api` is untouched; no aggregate/repository machinery is introduced.

- **S4 purity target is now unambiguous**: the 3 named deep couplings relocate; `@Component`-on-`@Service`
  stays; `rules/` and `repair/` work sequences after a #420 follow-on check.

- **S5 test-strategy target is now unambiguous**: 14 test-side `internal` reaches in
  5 files are removed; each context gets a `@ApplicationModuleTest`; `@SpringBootTest`
  reserved for e2e.

- **S7 carve-out reconciliation target is now unambiguous**: the listed entries are
  removed or annotated; `architecture.md` consolidates the 4 structural docs
  ([adr-001-single-agent-design.md](./adr-001-single-agent-design.md) frozen).
