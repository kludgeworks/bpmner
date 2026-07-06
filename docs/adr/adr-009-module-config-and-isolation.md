# ADR-009: Capability-owned config & module isolation

**S4:** Each capability module owns its own `@ConfigurationProperties` class at its root package
(e.g. `BpmnLoggingConfig`, `BpmnRepairConfig`, `BpmnReadinessConfig`, `BpmnAlignmentConfig`,
`BpmnRulesConfig`, `BpmnAuthoringConfig`, `BpmnContractConfig`). The config module is dissolved;
there is no central config class. This is the re-point target for the dead ADR-006 Decision 1
config-registration rule (retired by #451 S4).

**Logging-config home:** `BpmnLoggingConfig` home stays in `conformance`; `telemetry` is
event-surface-pure and does not consume it. A per-consumer logging-config split remains an open
re-decision (tracked as a TODO in `BpmnLoggingConfig`).

**Port-fronting (disposition a):** Root-package `internal.domain` services needed by multiple
modules are fronted by a port in the owning module's root — `BpmnDefaultFlowPort`,
`BpmnContractFidelityPort`, `BpmnRequestResolutionPort` front `DefaultFlowAssigner`,
`BpmnContractFidelityChecker`, `BpmnRequestResolver` (all in `authoring.internal.domain`).
Cross-module callers inject the **port**, not the impl.

**Bootstrap-mode tiers:** Module-test `BootstrapMode` tiers — **Tier 2** (`DIRECT_DEPENDENCIES`,
mock transitive non-collaborators via `@MockitoBean`, e.g. `layout`) / **Tier 3**
(`ALL_DEPENDENCIES`, deep integrators requiring all transitive beans live, e.g. `repair`).
See ADR-006 **gate 4‴**.

Origin: epic #451 (S4/S7/S8/S9 module reshape). See `../architecture.md` §5, §6.
