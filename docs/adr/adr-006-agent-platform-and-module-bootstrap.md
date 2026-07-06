# ADR-006: Agent platform wiring & module-test bootstrap

**Decision 2:** Capability modules obtain the real embabel `AgentPlatform` via `@EnableAgents`
(the agent platform is app-wired, not stubbed in module tests).

**gate 4‴:** Module `@ApplicationModule` tests select their `BootstrapMode` per the ADR-009
(bootstrap tiers) rule — `DIRECT_DEPENDENCIES` where all collaborators are direct (e.g.
`conformance`, `alignment`, `readiness`, `contract`); `ALL_DEPENDENCIES` for deep integrators
whose transitive beans must be live (e.g. `pipeline`, `telemetry`, `authoring`, `repair`).

**Track A:** `AgentDeploymentValidator` lives in `pipeline/internal/adapter/inbound/` and
resolves within `ALL_DEPENDENCIES` bootstrap without a stub.

Origin: epic #424 S7 (agent-platform & bootstrap decisions); config-registration decision reversed by #451 S4 (see ADR-009 S4). See `../architecture.md` §5.
