# ADR-004: Module placement rule & boundaries

A type's home is decided by what language it speaks and which slice owns it (**§6** placement
rule, enforced by `BpmnerArchitectureTest`):

- **`bpmn/` root** — annotation-free BPMN graph interfaces, rule SPI, repair/request vocabulary.
  No Spring, Jakarta, or Embabel imports. Kernel-minimality enforced by the
  `bpmn kernel is free of framework, IO, and cross-module dependencies` gate (`DOMAIN_ALLOWLIST`).
- **`bpmn/internal/model/`** — Jackson-bound concrete implementations of the root interfaces.
- **Capability root** — `@ConfigurationProperties`, `@DomainEvent`, `@PrimaryPort`, `@SecondaryPort`
  for that capability only.
- **`<cap>/internal/domain/`** — domain services (`@Service`); no Spring stereotypes beyond
  `@Component`/`@Service`, no IO, no cross-module imports.
- **`<cap>/internal/adapter/inbound/`** — `@PrimaryAdapter` classes (CLI, REST, agent actions).
- **`<cap>/internal/adapter/outbound/`** — `@SecondaryAdapter` classes (integrations).
- **`pipeline/`** — the single `@Agent` + `@Action` shims + HTTP/shell inbound adapters.
- **No technical-layer modules** (`api`, `config`, `core`, `util`, …). If a type cannot be
  named in the BPMN ubiquitous language or as an explicit adapter/agent, it finds its capability
  owner — never a new layer bucket.

Origin: epic #424 S7, reshaped by epics #451 and #539. See `../architecture.md` §6.
