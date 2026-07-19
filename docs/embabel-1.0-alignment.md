# Embabel 1.0 dependency alignment policy

Records the version-pinning decisions for the Embabel 0.4.0 → 1.0-line migration
(epic [#588](https://github.com/kludgeworks/bpmner/issues/588), stage 1). For the
migration's scope and staging, see `plans/588/ARCHITECTURE.md` in the `gb` workspace —
this note only records the pins and their provenance, not the plan.

## RC1-vs-GA decision (S1 exit)

**Target: `1.0.0-RC1`.** Re-checked Maven Central at build time
(`embabel-agent-dependencies` `maven-metadata.xml`): `1.0.0-RC1` remains the latest
published version — no `1.0.0` GA exists yet. Re-run this check on the next Embabel bump
and adopt GA if it has landed by then.

## Embabel modules — BOM-governed

`com.embabel.agent:embabel-agent-dependencies:1.0.0-RC1` is imported as a `maven.install`
`boms` entry and is the single source of truth for every `com.embabel.agent:*` artifact
version (`MODULE.bazel`). Per-coordinate version suffixes were dropped from the Embabel
starter coordinates so they inherit from the BOM.

## Spring Boot / Spring AI — pinned with provenance, not BOM-governed

The Embabel BOM does not manage Spring versions (no `<parent>`, no Spring BOM import), and
the aligning `com.embabel.build:embabel-build-dependencies` is not published to Maven
Central. The two pins below are instead **derived from the Embabel RC1 artifact POMs**,
which hard-pin these versions internally (e.g. `embabel-agent-api-1.0.0-RC1.pom`):

- `SPRING_BOOT_VERSION = "3.5.14"`
- Spring AI: `org.springframework.ai:spring-ai-bom:1.1.7` (imported explicitly as a
  `maven.install` `boms` entry so `org.springframework.ai:*` resolves to one version
  rather than resolving invisibly under `version_conflict_policy = "pinned"`)

**Re-derive both on every future Embabel bump** by reading the new RC/GA's
`embabel-agent-api` POM — do not choose them independently, since no Embabel-published
BOM covers Spring.
