# bpmner — Architecture

> **Status:** Seeded in epic #424 S1. Sections marked **\[stub\]** will be filled in as
> S3–S7 land. Until then, the linked legacy docs are the authoritative source for
> those sections. See [adr-002-module-architecture.md](./adr-002-module-architecture.md)
> for the decisions this document captures and the S7 consolidation target.
>
> Do not delete or merge the legacy docs until S7. Do not edit the frozen ADRs
> ([adr-001-single-agent-design.md](./adr-001-single-agent-design.md),
> [adr-002-module-architecture.md](./adr-002-module-architecture.md)).

---

## 1. Context map (authoritative)

Three bounded contexts, one application layer, delivery adapters, and cross-cutting
infrastructure. Decided in [adr-002-module-architecture.md §D-map](./adr-002-module-architecture.md).

| Context / Layer | Role | Owns | Today's modules |
| --- | --- | --- | --- |
| **Authoring** | Core domain | The BPMN process graph as a behaviour-bearing domain object; its structural invariants; generation / contract drafting | `core` model → Authoring (S3), `generation`, `contract`, `layout` |
| **Conformance** | Supporting domain | Rule catalogue + evaluation + repair; own ubiquitous language (rule id / severity / capability, Pkl-fed) | `rules`, `validation`, `repair`, `alignment` |
| **Intake / Readiness** | Supporting domain | Request readiness + clarification subdomain | `readiness` |
| **Generation Orchestration** | Application layer — not a domain context | Single `BpmnGenerationAgent`, GOAP wiring, `@Action` shims | `orchestration` |
| **Delivery adapters** | Inbound/primary adapters — not contexts | HTTP, shell entrypoint | `web`, shell entrypoint |
| **Cross-cutting** | Infrastructure / sink — not contexts | Config, observability | `config`, `observability` |

Key decisions:

- `web` (3 files, 104 lines of HTTP↔port glue) is a **driving/primary adapter** of
  Generation Orchestration, not a bounded context.
- `core/` shrinks to a **minimal published vocabulary** (S3). The process-graph model
  and its behaviour move into **Authoring**. `api` is untouched.
- The BPMN process graph becomes a **behaviour-bearing domain object** — model-intrinsic
  invariants (`validate()`-style structural checks, ownership) move onto the graph types.
  No aggregate/repository/persistence machinery. (Embabel §6.1; `plans/424/ARCHITECTURE.md §4`.)

---

## 2. Agent design and control flow \[stub — see legacy docs\]

The orchestrating agent design, GOAP control flow, and action-chain details are
documented in:

- [agents.md](./agents.md) — the three agents (`BpmnGenerationAgent`, `BpmnReadinessAgent`,
  `BpmnLayoutAgent`), their actions, and the `@State` machine.
- [goap-lifecycle.md](./goap-lifecycle.md) — how the GOAP planner threads actions by type,
  cost ordering, failure modes, and the static path-completion validator.
- [pipeline-architecture.md](./pipeline-architecture.md) — the end-to-end pipeline from
  `UserInput` through to `BpmnResult`, blackboard types, and the pipeline/ports contract.

These docs will be folded into this file in S7 (epic #424, [ADR-3 in `adr-002-module-architecture.md`]).

---

## 3. Module shape, ports, and hexagonal layering \[stub — see legacy doc\]

The hexagonal layering conventions, `@PrimaryPort` / `@SecondaryPort` /
`@PrimaryAdapter` / `@SecondaryAdapter` usage, the onion-architecture invariants, and
the annotation decision guide are in:

- [hexagonal-architecture.md](./hexagonal-architecture.md)

This doc will be folded into this file in S7. Until then it is the authoritative
source for module structure, annotation idioms, and enforcement details.

---

## 4. Enforcement

The boundary enforcement stack is defined in
[adr-002-module-architecture.md §D-enforce](./adr-002-module-architecture.md):

- `BpmnerModulithTest` — `ApplicationModules.of(…, excludeBazelTestClasses).verify()`
- `BpmnerArchitectureTest` — `ensureOnionSimple`, `ensureHexagonal(LENIENT)`,
  4 bespoke pin rules, `excludeBazelTestClasses`
- `src/test/resources/archunit_ignore_patterns.txt` — Kotlin-synthetic suppressions

New rules (cross-module `internal` boundary gate, framework-purity gate) will be added
in S2. Carve-out reconciliation happens in S7.

---

## 5. ADR log

| ADR | Title | Status |
| --- | --- | --- |
| [ADR-001](./adr-001-single-agent-design.md) | Single-Agent Design for BPMN Generation | Accepted (epic #399, #409, 2026-06-15) |
| [ADR-002](./adr-002-module-architecture.md) | Subdomain Context Map and Rich-Graph Domain Model | Accepted (epic #424, S1, 2026-06-17) |
