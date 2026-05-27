# Linter Module

This directory holds the bpmner rule catalog — the source of truth for every BPMN
validation rule the pipeline evaluates.

Rules are authored in [Pkl](https://pkl-lang.org/) under [`pkl/`](pkl/). The Bazel
build evaluates them at compile time and generates Java types that
[`PklRuleCatalog`](../src/main/kotlin/dev/groknull/bpmner/rules/internal/domain/PklRuleCatalog.kt)
loads at startup; the in-process [`RuleEngine`](../src/main/kotlin/dev/groknull/bpmner/rules/RuleEngine.kt)
then evaluates each rule against the `BpmnDefinition` directly.

The previous TypeScript/`bpmnlint` codebase (rule implementations, GraalJS bundle,
static-rule registry) was removed in #241 phase 2G. GraalJS now only lives inside
the [`layout`](../layout/) module (auto-layout JS bundle).

## Layout

- [`pkl/`](pkl/) — Pkl schema (`schema/BpmnRule.pkl`, `CheckPrimitive.pkl`,
  `RuleCategory.pkl`) and one `.pkl` file per rule. The Bazel `pkl_java_library`
  emits Java types that the Kotlin side imports.
- [`docs/`](docs/) — Vale prose-style sources (templates, toolbox guides) used by
  the rule-docs generator.

See [`pkl/README.md`](pkl/README.md) for the rule-authoring workflow and the Pkl
test fixtures.
