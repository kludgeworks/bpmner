# Linter Module

This directory holds the bpmner rule catalog — the source of truth for every BPMN
validation rule the pipeline evaluates.

Rules are authored directly as hand-written Kotlin Spring `@Bean BpmnRule` factories
(`*RuleConfig.kt`), collected at startup by
[`BeanRuleRegistry`](../src/main/kotlin/dev/groknull/bpmner/ruleset/internal/domain/beans/BeanRuleRegistry.kt);
the in-process [`RuleEngine`](../src/main/kotlin/dev/groknull/bpmner/ruleset/RuleEngine.kt)
then evaluates each rule against the `BpmnDefinition` directly.

GraalJS is not used anywhere in the repository; rule evaluation and BPMN layout
(the [`layout`](../src/main/kotlin/dev/groknull/bpmner/layout/) module) are both
entirely JVM-native.

## Layout

- [`pkl/`](pkl/) — Pkl schema (`schema/BpmnRule.pkl`, `CheckPrimitive.pkl`,
  `RuleCategory.pkl`) and one `.pkl` file per rule. The Bazel `pkl_java_library`
  emits Java types that the Kotlin side imports.
- [`docs/`](docs/) — Vale prose-style sources (templates, toolbox guides) used by
  the rule-docs generator.

See [`docs/rule-authoring-guide.md`](docs/rule-authoring-guide.md) for the
rule-authoring workflow and the Pkl test fixtures.
