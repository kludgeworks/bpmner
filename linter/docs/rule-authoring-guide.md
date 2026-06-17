# Authoring BPMN Rules

bpmner has three tiers of rule authoring, mapped to where the rule executes and how it's deployed.

| Tier | Where rules live | What they execute as | Deployment |
| --- | --- | --- | --- |
| **1** | `src/main/kotlin/dev/groknull/bpmner/rules/internal/domain/compiled/` | Compiled Kotlin `@Component` `BpmnRule` beans | Part of the bpmner JAR |
| **2** | `linter/pkl/rules/*.pkl` | Pkl declarations adapted to `BpmnRule` at startup | Bundled in the bpmner JAR (today) |
| **3** | Plugin JAR with `BpmnRule` beans | Compiled Kotlin in an external JAR | **Not yet implemented** — see "Tier 3" below |

The three tiers share one interface — `BpmnRule` in [`api/BpmnRule.kt`](../../src/main/kotlin/dev/groknull/bpmner/api/BpmnRule.kt) — and the same diagnostic types ([`api/RuleDiagnostic.kt`](../../src/main/kotlin/dev/groknull/bpmner/api/RuleDiagnostic.kt)). The choice between tiers is about *who authors* the rule and *how it's deployed*, not what it can express.

## Tier 1 — Compiled Kotlin rules

Use this tier when:

- The rule needs to operate on the typed `BpmnDefinition` graph with custom Kotlin logic.
- It can't be expressed via the Pkl check primitives (`PropertyEquals`, `PropertyPattern`, `Composite`, the NLP primitives).
- It needs access to a Kotlin domain service (e.g. ownership lookup, fingerprint computation).

### Anatomy

A Tier 1 rule is a `@Component` class implementing `BpmnRule`:

```kotlin
@Component
internal class DanglingEdgeRule : BpmnRule {
    override val id: String = "def-dangling-edges"
    override val metadata: RuleMetadata = RuleMetadata(
        id = id,
        name = "Dangling Edges",
        slug = "dangling-edges",
        category = "Definition",
        intent = "Ensure every sequence flow connects existing BPMN nodes and does not self-reference.",
        forModellers = "Connect each flow to two distinct elements that exist in the process.",
        forAI = "Validate sequenceFlow sourceRef and targetRef against node ids before returning BPMN.",
        targetElements = listOf("bpmn:SequenceFlow"),
        errorMessages = mapOf(
            "def-dangling-source" to "Sequence flow sourceRef must match an existing node id.",
            "def-dangling-target" to "Sequence flow targetRef must match an existing node id.",
            "def-self-reference" to "Sequence flow sourceRef and targetRef must be different.",
        ),
        // ... severity, repair metadata, etc.
    )

    override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> {
        // your logic here
    }
}
```

Find it under `src/main/kotlin/dev/groknull/bpmner/rules/internal/domain/compiled/` for in-tree examples (`DanglingEdgeRule.kt`, `RequiredEventsRule.kt`, etc.).

### `RuleMetadata` essentials

| Field | Required? | What it does |
| --- | --- | --- |
| `id` | yes | Stable rule identifier (e.g. `def-dangling-edges`). Used as the key for severity overrides. |
| `name` / `slug` / `category` | yes | Surface metadata for docs and listings. |
| `intent` | yes | One-line statement of what the rule enforces. |
| `forModellers` / `forAI` | yes | Audience-specific phrasing. `forAI` is consumed by the prompt-side LLM repair tier. |
| `targetElements` | yes | BPMN element types this rule applies to. |
| `errorMessages` | yes | Map from `diagnosticCode` to human-readable message. A rule can emit multiple codes. |
| `severity` | optional (defaults `WARNING`) | Default severity. The active profile + user overrides modify this. |
| `repair` | optional (defaults `LLM_MODEL_PATCH`) | `RepairKind` + `RepairSafety` + handler name. See the [GOAP lifecycle doc](../../docs/goap-lifecycle.md#the-pkl-side-repair-contract). |
| `aliases` | optional | Alternative ids for backward compatibility. |

### Emitting a diagnostic

`evaluate(ctx)` returns `List<RuleDiagnostic>`. Build each diagnostic with the registered `diagnosticCode` (must match an `errorMessages` key) and the violating element:

```kotlin
override fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic> = buildList {
    for (edge in ctx.definition.sequences) {
        if (edge.sourceRef !in nodeIds(ctx)) {
            add(metadata.diagnosticForViolation(
                violation = RuleViolation(
                    ruleId = id,
                    diagnosticCode = "def-dangling-source",
                    elementId = edge.id,
                    detail = "no node with id '${edge.sourceRef}'",
                ),
            ))
        }
    }
}
```

### Local-fix handler (for `LOCAL_MODEL_FIX` rules)

If your rule sets `repair.kind = LOCAL_MODEL_FIX`, it must also name a `handler`. The handler is a separate `@Component` implementing `LocalModelFixHandler`:

```kotlin
@Component("stripTypeWords")
internal class StripTypeWordsHandler(
    private val abbreviations: AbbreviationConfig,
) : LocalModelFixHandler {
    override fun apply(diagnostic: BpmnDiagnostic, definition: BpmnDefinition): BpmnDefinition? {
        // mutate the definition; return null if unable to fix
    }
}
```

The handler name in the rule's `repair.handler` field must match the `@Component("name")` qualifier. `BpmnLocalRepairCapabilityValidator` fails Spring context refresh at startup if a `LOCAL_MODEL_FIX` rule references an unregistered handler.

### Testing

In-tree convention:

- One test class per rule in `src/test/kotlin/.../rules/...Test.kt`.
- Build a minimal `BpmnDefinitionContext` from `TestBpmnFixtures`.
- Assert on the emitted `RuleDiagnostic` list.

See `TopologyHandlersTest`, `RequiredEventsRuleTest`, `DanglingEdgeRule` tests for examples.

## Tier 2 — Pkl rules

Use this tier when:

- The rule can be expressed via one of the bundled check primitives (`PropertyEquals`, `PropertyPattern`, `NlpClassification`, `Composite`, ...).
- You want to declare the rule without writing Kotlin.
- The rule is content-rule rather than structural (label conventions, naming patterns, advisory linting).

### Anatomy

A Tier 2 rule is a `.pkl` file under `linter/pkl/rules/` that `amends BpmnRule.pkl`:

```pkl
amends "../schema/BpmnRule.pkl"

import "../schema/RuleCategory.pkl"
import "../schema/CheckPrimitive.pkl"

name = "Business Meaningful Label"
category = RuleCategory.Name

intent = "Encourage business-readable labels over technical identifiers."
forModellers = "Choose names meaningful to business stakeholders..."
forAI = "Detect labels containing technical patterns such as underscores..."

targetElements = List("bpmn:Task", "bpmn:SubProcess")
severity = "warning"

errorMessages = Mapping(
  "name-business-meaningful-label" -> "Label contains technical patterns; prefer a business-readable name."
)

checkPrimitive = "PropertyPattern"
checkConfig = new CheckPrimitive.PropertyPatternConfig {
  property = "name"
  pattern = "[A-Z][a-z]+(?:[A-Z][a-z]+)*"
  description = "PascalCase technical labels are not business-readable"
}

repair {
  kind = "LLM_MODEL_PATCH"
}
```

See `linter/pkl/rules/` for the catalog — every file there is an example.

### Available check primitives

| Primitive | What it checks |
| --- | --- |
| `PresenceCheck` | Emits one diagnostic for each targeted element that exists |
| `PropertyEquals` | Element's named property has a specific value |
| `PropertyPattern` | Element's named property matches/violates a regex |
| `PropertyExists` | Element has a non-blank named property |
| `NlpClassification` | NLP primitive (POS tagging, verb-object structure, etc.) — see `BpmnNlp` |
| `Composite` | AND/OR of sub-checks; lets you compose multiple primitives |
| `LlmCheckRule` | The check runs as an LLM prompt against the definition. Used for advisory rules that need semantic judgment. |

Definitions live in `linter/pkl/schema/CheckPrimitive.pkl`. The Kotlin-side dispatcher (`MappedCheck`) handles each variant.

### Rule id, automatically derived

`id = category.shortCode + slug` is computed in the Pkl schema. Category short codes: `act`, `art`, `assoc`, `data`, `evt`, `flow`, `gen`, `gtw`, `lane`, `msg`, `name`, `pool`. So `name = "Business Meaningful Label"` + `category = RuleCategory.Name` → `id = "name-business-meaningful-label"`. Use that id in severity overrides and profile files.

## Shared convention lists (`BpmnerLintConfig.pkl`)

The top-level `linter/pkl/BpmnerLintConfig.pkl` schema defines the modeller-owned convention lists that are loaded from `bpmner.pkl` and injected into Kotlin rule beans. Keep the Kotlin mirror in `BpmnerLintConfig.kt` aligned with the Pkl fields and defaults.

| Field | Kotlin consumer |
| --- | --- |
| `discouragedLeadingVerbs` | `ActivityRuleConfig.actDiscouragedBusinessVerbs` |
| `elementTypeWords` | `NameRuleConfig.nameNoElementTypeWords`, `DataRuleConfig.dataNoTypeWordsInDataName`, and `StripTypeWordsHandler` |
| `allowedAcronyms` | `NameRuleConfig.nameUncommonAbbreviations` |
| `technicalTokens` | `NameRuleConfig.nameBusinessMeaningfulLabel` |
| `discouragedBpmnTypes` | `GeneralRuleConfig.genBpmnSubset` |

The packaged `linter/pkl/bpmner.pkl` amends `BpmnerLintConfig.pkl` and is the default `modulepath:` source. Team-specific convention files should amend the same template and be supplied to the application with `bpmner.rules.config-uri=file:/absolute/path/to/bpmner.pkl`.

### Adding a new Pkl rule

1. Create `linter/pkl/rules/<PascalName>.pkl` amending `BpmnRule.pkl`.
2. Re-run `bazel build //linter/pkl:rules_index_pkl` — the `rules_index` macro regenerates `RulesIndex.pkl` from the glob. No hand-listing.
3. Write a **per-rule test class** in `src/test/kotlin/dev/groknull/bpmner/rules/internal/domain/pkl/<PascalName>Test.kt` (see next section).
4. If the rule is `severity = "warning"`, no extra step is needed for the `strict` profile — `RuleProfileFactory.computeStrictBaseline()` reads the live `BeanRuleRegistry` at startup and automatically promotes every WARNING-severity rule to ERROR. New Pkl rules are registered in the bean catalog by the `PklRuleCatalog` loader, so they appear in the strict baseline without any manual maintenance.

### Writing a per-rule test

Every active Pkl rule should have a dedicated test class under `src/test/kotlin/dev/groknull/bpmner/rules/internal/domain/pkl/`. The class uses `PklRuleTestSupport` to load the **production** rule from the live `PklRuleCatalog` — no synthetic configuration, no hand-built primitive instance. This is the consistency point: every test exercises what production exercises.

Worked examples shipped with the codebase:

- `ActivityLabelCapitalizationTest.kt` — single `PropertyPatternCheck` rule (regex-only)
- `BusinessMeaningfulLabelTest.kt` — `PropertyPatternCheck` with `forbiddenVocabulary`
- `UncommonAbbreviationsTest.kt` — `PropertyPatternCheck` with `allowedVocabulary` + repair-metadata round-trip
- `VerbObjectNameTest.kt` — `CompositeCheck` binding two sub-checks (`tooShort` + `missingVerb`)

The canonical shape:

```kotlin
internal class MyRuleTest {
    private val rule = loadRule("<rule-id>")

    @Test
    fun `clean case passes`() {
        assertSilent(rule, context(nodes = listOf(BpmnUserTask("t", "Submit form"))))
    }

    @Test
    fun `violation flags`() {
        assertFires(
            rule,
            context(nodes = listOf(BpmnUserTask("t", "Submit_form"))),
            expectedElementIds = listOf("t"),
        )
    }
}
```

Helpers available on `PklRuleTestSupport`:

| Helper | Purpose |
| --- | --- |
| `loadRule(ruleId)` | Returns the production `BpmnRule`. Fails fast if the id isn't in the registry — usually because the rule is deferred (`checkPrimitive == null`) or `severity = "off"`. |
| `context(nodes, edges?)` | Builds a `BpmnDefinitionContext`. When `edges` is null, nodes are chained in order. |
| `assertFires(rule, ctx, expectedElementIds, expectedDiagnosticCode?)` | Asserts the rule fires on exactly the listed elements (order-insensitive). Optionally pins the diagnostic code (needed for composite rules). |
| `assertSilent(rule, ctx)` | Asserts the rule emits no diagnostics. |
| `evaluate(rule, ctx)` | Returns the raw diagnostic list for custom inspection (e.g. message substrings, repair metadata). |

Cover positive (rule fires) and negative (rule doesn't fire) cases per detection category. Use **single-fault inputs** — one violation kind per test method — so the failure message points directly at which detection broke.

#### Surfacing parser-level signals

Some rules check document-level metadata that the XML parser surfaces *outside* the per-node walk:

| Signal | Source | Helper argument | Worked example |
| --- | --- | --- | --- |
| BPMNDI diagram count | `BpmnDefinition.diagramCount` | `context(nodes, diagramCount = N)` | `NoDuplicateDiagramsTest` (#282) |
| Unrecognized BPMN element types (Choreography, Conversation, Transaction, etc.) | `BpmnUnrecognizedNode` in `nodes` | Pass `BpmnUnrecognizedNode("id", null, "bpmn:<Type>")` directly in the `nodes` list | `BpmnSubsetTest` (#282) |
| Unrecognized event-definition types (e.g. `bpmn:CompensateEventDefinition`) | `BpmnUnrecognizedEventDefinition` carried by a `BpmnEvent` | `BpmnStartEvent(eventDefinition = BpmnUnrecognizedEventDefinition("bpmn:<Type>"))` | `BpmnSubsetTest` (#282) |

The parser-as-structure / rule-as-policy refactor (#282) means **every type the parser sees flows through the same path** — typed nodes go into `nodes`, unrecognized ones go in via `BpmnUnrecognizedNode`, and the rule engine treats them uniformly. Tests don't need helper extensions for new exotic types; construct the appropriate sealed subtype directly.

### Filesystem-deployed Pkl rules (not yet supported)

A `bpmner.rules.custom-dir` setting for filesystem rule discovery is tracked separately and is not currently wired. Today, Tier 2 rules ship in the bpmner JAR.

## Tier 3 — Plugin JARs (designed, not yet deployed)

`BpmnRule` is annotation-free (the `api` package has zero Spring / Embabel imports — enforced by `ApiAnnotationFreeTest`) precisely so external authors can write rules in their own JAR. Today the **interface contract** is fully Tier-3-ready:

- An external author writes a `@Component`-annotated `BpmnRule` implementation in a separate Gradle/Maven project.
- The class produces a JAR that depends only on bpmner's `api` module (Embabel and Spring stay out of the contract).

What's missing is the **deployment mechanism**:

- bpmner's Bazel `springboot()` macro currently emits a `JarLauncher` uber-JAR. `loader.path` only works with `PropertiesLauncher`.
- Without `PropertiesLauncher`, dropping a plugin JAR onto the classpath at runtime requires building it into the uber-JAR — defeating the point.

The dedicated follow-up issue tracks switching the launcher and reconciling with Bazel's runfiles isolation. Until then, treat Tier 3 as design intent: the contract is stable, the deployment is pending.

## Documenting Rules

All rules in the catalog are documented under `src/test/resources/rule-docs/`. The documentation is checked in as golden files, and a unit test (`RuleDocsGoldenTest`) asserts that the checked-in files match the rule metadata.

When you add a new rule, or update an existing rule's metadata (such as intent, guidance, name, etc.), you must regenerate the documentation:

```bash
bazel run //src/test:update_rule_docs
```

Commit the generated/updated Markdown files along with your code changes.

## Choosing a tier

```text
                    ┌──────────────────────────────────────────────┐
                    │  Does the rule need bespoke Kotlin logic     │
                    │  or access to a Kotlin domain service?        │
                    └──────────────────────────────────────────────┘
                              yes ─────────────────► Tier 1
                              no
                              ▼
                    ┌──────────────────────────────────────────────┐
                    │  Does the rule fit a check primitive          │
                    │  (PropertyEquals, PropertyPattern, NLP,       │
                    │  Composite, LlmCheckRule)?                    │
                    └──────────────────────────────────────────────┘
                              yes ─────────────────► Tier 2
                              no ──────────────────► Tier 1 (write the
                                                            primitive,
                                                            then Tier 2)
```

External operators today are limited to Tier 2 contributions made via PR (they can't ship their own JAR yet). The follow-up issue for Tier 3 will change that.
