# ADR-023: The sanctioned `conformance→ruleset` ACL

**Decision 2:** `RuleEngineLintingAdapter` (`conformance/internal/adapter/outbound/`) is the
**sole** class in `conformance` allowed to reach `ruleset`'s `@PrimaryPort`s (`RuleEngine`,
`RuleRegistry`). It deliberately carries **no** `@SecondaryAdapter` — it is an Anti-Corruption
Layer, not a plain secondary adapter. A `BpmnerArchitectureTest` pin asserts this boundary;
any other `conformance` class importing those ports fails CI. The deprecated `LlmValidator`
is a named audited exception pending removal.

**Decision 1.1:** `ConventionsLoader` (`ruleset/internal/domain/`) constructor-injects
`BpmnRulesUriConfig` to create the `ruleset→config USES_COMPONENT` edge required for
`DIRECT_DEPENDENCIES` module isolation. (Config type changed from `BpmnConfig` to
`BpmnRulesUriConfig` when the `config` module was dissolved in epic #451 S4.)

Origin: epic #424 S7, updated in epic #451 S4. See `architecture.md` §6.
