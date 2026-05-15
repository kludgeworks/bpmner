# Linter Module

This module contains the custom `bpmnlint` rules used by `bpmner`.

The primary artifact is not an npm package. It is a Bazel-built JavaScript bundle embedded into the JVM app and executed through GraalJS by [BpmnLintService](../src/main/kotlin/dev/groknull/bpmner/validation/internal/adapter/outbound/BpmnLintService.kt).

`linter/` is still a standalone Node package. It owns its own `package.json` and `tsconfig.json`, while Bazel consumes that package in place.

## Runtime Shape

There are three layers:

1. `rules/*.ts`
   Custom plugin rule implementations.
2. `src/generated/static-rules.ts`
   Generated static resolver catalog for GraalJS bundling.
3. `src/linter-bundle.ts`
   GraalJS entrypoint that exposes `globalThis.BpmnLinterApi`.

`index.ts` is kept as a thin compatibility adapter for plugin-style Node tests. It is no longer the architectural center of the module.

## Build

Build the GraalJS bundle with Bazel:

```bash
bazel build //linter:bundle
```

The output is packaged into the application jar as `js/bpmnlint-bundle.js`.

Install or refresh local package metadata from inside `linter/`:

```bash
cd linter
pnpm install
```

## Tests

Run the linter-specific tests with Bazel:

```bash
bazel test //linter:all
```

Run Vale locally when changing rule prose:

```bash
pnpm vale:docs
bazel test //:vale_docs_test //:vale_fixtures_test
```

The test suite covers:

- direct rule tests across deterministic and heuristic rule coverage
- a bundle smoke test that exercises `BpmnLinterApi`

## Repair Architecture

For the end-to-end repair subsystem (Pkl contract, Bazel codegen, Kotlin orchestration, local-first vs LLM fallback), see [`docs/repair-architecture.md`](docs/repair-architecture.md).

## Adding or Changing a Rule

1. Add or update the rule implementation under `rules/`.
2. Update `src/rule-manifest.ts` with the rule id and severity.
3. Run a Bazel build or test.

```bash
bazel build //linter:bundle
```

Bazel generates `src/generated/static-rules.ts` automatically for both the bundle and the direct linter tests. The generator script is still available as an optional debugging tool:

```bash
cd linter
node ./scripts/generate-static-rules.mjs
```

4. Add or update fixtures under `test/fixtures/phase1/` or `test/fixtures/phase2/`.
5. Run `bazel test //linter:all`.

The manifest is the source of truth for custom rule ids and severities. `src/generated/static-rules.ts` is generated and should not be edited manually.

## Fixtures

Test fixtures live as `.bpmn` files under:

- `test/fixtures/phase1/`
- `test/fixtures/phase2/`

The fixture index modules live at `test/fixtures/phase1/index.ts` and `test/fixtures/phase2/index.ts`, importing those BPMN files as text so the tests stay readable and Bazel can bundle them with esbuild.

## Polyfills

`src/polyfills.ts` exists only to provide the minimal globals needed when the bundle runs inside GraalJS without a Node runtime. It is injected into the bundle via esbuild and should stay intentionally small.
