## 1. Goal

Add PMD Copy/Paste Detector checks to the Bazel build with a 100-token threshold and refactor all reported duplicate source blocks without suppressions.

## 2. Approach

Use `rules_pmd` for PMD distribution/tool resolution, then add a small repository-local Bazel `cpd_test` rule that invokes PMD CPD directly because the inspected `rules_pmd` 0.4.0 API exposes `pmd_test` for PMD static-analysis rulesets but no CPD rule. Wire the CPD checks into the existing root `ci` test suite alongside `detekt_check`, `ktlint_check`, buildifier, Vale, and TypeScript build tests. Refactor shared duplicated code into test fixtures and TypeScript helpers so CPD passes without ignores or suppression comments.

## 3. File Changes

- Modify `MODULE.bazel`: add `bazel_dep(name = "rules_pmd", version = "0.4.0")`, load/use the `@rules_pmd//pmd:extensions.bzl` module extension, and `use_repo(pmd, "net_sourceforge_pmd")` so Bazel has a PMD CLI repository available. This belongs near the existing rule dependencies and Maven/Node module extensions.
- Create `tools/pmd/BUILD.bazel`: declare the new tools package and any `java_binary`/exports required by the local CPD wrapper.
- Create `tools/pmd/cpd.bzl`: implement a Starlark test rule or macro, likely named `cpd_test`, with attributes for `srcs`, `language`, `minimum_tokens`, and `encoding`; generate a deterministic source file list and run PMD CPD with `--minimum-tokens 100`, `--language`, `--format text`, and `--fail-on-violation true`.
- Modify root `BUILD.bazel`: load `//tools/pmd:cpd.bzl`, add CPD tests for Kotlin and TypeScript sources, and add those targets to the existing `test_suite(name = "ci")` currently containing `:detekt_check`, `:typescript_build_test`, `//src:ktlint_check`, and `//src/test:ktlint_check`.
- Modify `src/BUILD.bazel`: expose Kotlin production sources through a CPD source filegroup, reusing the same `glob(["main/kotlin/**/*.kt"])` shape currently used for `detekt_srcs` and `ktlint_check`.
- Modify `src/test/BUILD.bazel`: expose Kotlin test sources through a CPD source filegroup, reusing the current `glob(["kotlin/**/*.kt"])` shape used by `detekt_srcs` and `ktlint_check`.
- Modify `linter/BUILD.bazel`: expose TypeScript CPD sources based on `LIB_TS_SRCS`, `TEST_TS_SRCS`, and `TYPE_DECL_SRCS`, excluding generated output such as `src/generated/static-rules.ts` and generated JSON because CPD should guard authored code.
- Modify `layout/BUILD.bazel`: expose TypeScript CPD sources based on `LIB_TS_SRCS` and `TEST_TS_SRCS`.
- Create `src/test/kotlin/dev/groknull/bpmner/TestBpmnFixtures.kt`: centralize repeated test BPMN definitions and graph helpers found in `BpmnValidationIntegrationTest`, `GenerationModuleTest`, `BpmnRefinementEngineTest`, and `BpmnRepairAgentTest`.
- Modify `src/test/kotlin/dev/groknull/bpmner/validation/BpmnValidationIntegrationTest.kt`: replace the duplicated local `toastDefinition` fixture with the shared fixture helper.
- Modify `src/test/kotlin/dev/groknull/bpmner/generation/GenerationModuleTest.kt`: replace the duplicated local `validDefinition` and, if compatible, graph construction with shared test fixture helpers.
- Modify `src/test/kotlin/dev/groknull/bpmner/repair/internal/domain/BpmnRefinementEngineTest.kt`: replace the repeated valid BPMN definition block with shared fixture helpers while keeping test-specific variations local.
- Modify `src/test/kotlin/dev/groknull/bpmner/repair/internal/adapter/inbound/BpmnRepairAgentTest.kt`: replace the repeated valid BPMN definition block with shared fixture helpers that still support `processId` and `processName` overrides.
- Modify `linter/src/rules/_helpers.ts`: add focused helpers for duplicated rule behavior, such as `startsWithVerbLike`, pool-boundary lookup, and cross-pool message/sequence flow predicates.
- Modify `linter/src/rules/msg-message-flow-name-pattern.ts`: import and use the shared verb-detection helper instead of defining local Wink NLP setup and `startsWithVerbLike`.
- Modify `linter/src/rules/evt-intermediate-event-not-action.ts`: import and use the shared verb-detection helper instead of defining a duplicate function.
- Modify `linter/src/rules/msg-message-flow-across-pools.ts`: import and use a shared pool-boundary helper for source/target pool lookup.
- Modify `linter/src/rules/flow-sequence-flow-within-pool.ts`: import and use the same shared pool-boundary helper.
- Modify `linter/src/rules/evt-message-start-has-message-flow.ts` and `linter/src/rules/gen-no-duplicate-diagrams.ts` only if CPD still reports a real shared block after the first TypeScript helper extraction.
- Create `tools/js/polyfills.ts` or another shared package-appropriate TypeScript file: consolidate the duplicated GraalJS Buffer/base64 polyfill currently present in both linter and layout bundles.
- Modify `linter/src/polyfills.ts`: replace the duplicated polyfill implementation with an import/re-export or thin call into the shared polyfill module, preserving esbuild injection behavior.
- Modify `layout/src/polyfills.ts`: replace the duplicated polyfill implementation with the same shared helper, preserving layout bundle behavior.
- Modify `tools/js/BUILD.bazel`: export/build the shared polyfill source if Bazel packages need explicit visibility for `linter` and `layout` esbuild inputs.
- Modify `linter/tsconfig.json`, `linter/tsconfig.bazel.json`, `layout/tsconfig.json`, or `layout/tsconfig.bazel.json` only if the shared TypeScript helper import requires an existing compiler include/path adjustment.
- Modify `MODULE.bazel.lock`: update through Bazel module resolution after adding `rules_pmd` explicitly and enabling the PMD extension.

## 4. Implementation Steps

### Task 1: Add the Bazel CPD tool

1. In `MODULE.bazel`, add `rules_pmd` 0.4.0 beside the existing Bazel rule dependencies and configure the PMD module extension so the PMD CLI artifact is available as `@net_sourceforge_pmd`.
2. In `tools/pmd/BUILD.bazel`, create a Bazel package for PMD tooling and expose any local wrapper target needed by the CPD rule.
3. In `tools/pmd/cpd.bzl`, implement `cpd_test` as a Bazel test rule that writes all `srcs` paths to an input list and invokes PMD CPD with `--minimum-tokens 100`, UTF-8 encoding, language-specific `--language`, and a text report.
4. Ensure the CPD rule fails on duplication and prints the CPD text report when violations exist, so developers can act on precise file/line output.

### Task 2: Wire CPD into authored source sets

5. In `src/BUILD.bazel`, add a `cpd_srcs` filegroup for `main/kotlin/**/*.kt` beside the existing `detekt_srcs` filegroup.
6. In `src/test/BUILD.bazel`, add a `cpd_srcs` filegroup for `kotlin/**/*.kt` beside the existing `detekt_srcs` filegroup.
7. In `linter/BUILD.bazel`, add a `cpd_srcs` filegroup over authored TypeScript files, using existing `LIB_TS_SRCS`, `TEST_TS_SRCS`, and `TYPE_DECL_SRCS`, and excluding generated files.
8. In `layout/BUILD.bazel`, add a `cpd_srcs` filegroup over authored TypeScript files, using existing `LIB_TS_SRCS` and `TEST_TS_SRCS`.
9. In root `BUILD.bazel`, add `cpd_test` targets for Kotlin production/test sources and TypeScript linter/layout sources. Use `minimum_tokens = 100` for every target.
10. Add the new CPD targets to the root `ci` suite so `bazel test //:ci` enforces duplication checks.

### Task 3: Refactor duplicated Kotlin test fixtures

11. Create `src/test/kotlin/dev/groknull/bpmner/TestBpmnFixtures.kt` with reusable functions for the common three-node toast/test `BpmnDefinition`, a helper for standard `BpmnBounds`/`BpmnWaypoint` values, and graph construction helpers where the same `ComposedProcessGraph`/`LaidOutProcessGraph` setup is repeated.
12. Update `BpmnValidationIntegrationTest.kt` to call the shared toast definition helper and remove its local `toastDefinition` block.
13. Update `GenerationModuleTest.kt` to call the shared valid definition helper and remove its local `validDefinition` block; use the shared graph helper if it matches the test’s `BpmnRequest("test")` behavior.
14. Update `BpmnRefinementEngineTest.kt` to call the shared valid definition helper for the common valid process while preserving local fixtures like `joinForkDefinition` that model different topology.
15. Update `BpmnRepairAgentTest.kt` to call the shared valid definition helper with `processId`/`processName` overrides, preserving test-specific corrected-definition values.

### Task 4: Refactor duplicated TypeScript rules and polyfills

16. In `linter/src/rules/_helpers.ts`, add `startsWithVerbLike(name: string): boolean` using the existing Wink NLP dependencies already used in rule files.
17. Replace local Wink NLP setup and local `startsWithVerbLike` definitions in `msg-message-flow-name-pattern.ts` and `evt-intermediate-event-not-action.ts` with imports from `_helpers.ts`.
18. In `linter/src/rules/_helpers.ts`, add a helper that returns source/target pool IDs for a flow from `getDefinitions` and `getPoolIdForNode`.
19. Replace duplicated pool-boundary lookup in `msg-message-flow-across-pools.ts` and `flow-sequence-flow-within-pool.ts` with that helper.
20. Move the duplicated GraalJS base64/Buffer polyfill from `linter/src/polyfills.ts` and `layout/src/polyfills.ts` into a shared TypeScript module under `tools/js`, then make both package-local polyfill files call the shared installer function.
21. Update `linter/BUILD.bazel` and `layout/BUILD.bazel` esbuild `srcs`/`deps` as needed so the shared polyfill file is available to both bundles.

### Task 5: Verify and iterate on CPD output

22. Run each new CPD target individually and refactor any remaining CPD findings by extracting helpers or tightening test fixtures; do not add CPD exclusions or suppression markers.
23. Run formatting/lint checks affected by refactors: Kotlin ktlint, TypeScript build, and buildifier.
24. Run the root CI suite to verify CPD is enforced with the existing quality gates.

## 5. Acceptance Criteria

- `bazel test //:cpd_kotlin_main` exits 0 and runs PMD CPD with `minimum_tokens = 100` against `src/main/kotlin/**/*.kt`.
- `bazel test //:cpd_kotlin_test` exits 0 and runs PMD CPD with `minimum_tokens = 100` against `src/test/kotlin/**/*.kt`.
- `bazel test //:cpd_typescript_linter` exits 0 and runs PMD CPD with `minimum_tokens = 100` against authored `linter/**/*.ts` files, excluding generated sources.
- `bazel test //:cpd_typescript_layout` exits 0 and runs PMD CPD with `minimum_tokens = 100` against authored `layout/**/*.ts` files.
- `bazel test //:ci` includes all CPD targets and exits 0.
- No CPD suppression comments, ignore-list suppressions, or excluded duplicate source regions are added to make violations pass.
- The repeated BPMN three-node valid definition block is no longer copied across `BpmnValidationIntegrationTest.kt`, `GenerationModuleTest.kt`, `BpmnRefinementEngineTest.kt`, and `BpmnRepairAgentTest.kt`.
- The duplicated TypeScript `startsWithVerbLike` implementation exists in one shared helper and both affected rule files import it.
- The duplicated TypeScript source/target pool lookup logic exists in one shared helper and both affected rule files import it.
- The duplicated GraalJS Buffer/base64 polyfill implementation exists in one shared module and both linter/layout bundle injection files delegate to it.
- `bazel test //src:ktlint_check //src/test:ktlint_check` exits 0 after Kotlin fixture refactors.
- `bazel test //:typescript_build_test` exits 0 after TypeScript helper/polyfill refactors.
- `bazel test //:buildifier_check` exits 0 after Bazel file changes.

## 6. Verification Steps

- Run `bazel test //:cpd_kotlin_main //:cpd_kotlin_test //:cpd_typescript_linter //:cpd_typescript_layout` to verify CPD itself.
- Run `bazel test //src:ktlint_check //src/test:ktlint_check` to verify Kotlin formatting after fixture extraction.
- Run `bazel test //:typescript_build_test //linter:lint_test //linter:smoke_test //layout:smoke_test` to verify TypeScript compilation and bundle behavior after helper/polyfill extraction.
- Run `bazel test //:buildifier_check` to verify Bazel formatting.
- Run `bazel test //:ci` as the final acceptance command.
- If CPD reports additional violations after the first refactor pass, inspect each reported file/line pair and refactor repeated logic into an existing local helper or a new narrow helper in the affected package.

## 7. Risks & Mitigations

- Risk: `rules_pmd` 0.4.0 resolves PMD 6.55.0 by default, while CPD language support may be narrower than the latest PMD 7 series. Mitigation: start with the resolved `rules_pmd` PMD distribution; if Kotlin or TypeScript CPD language support is missing, configure the `rules_pmd` PMD extension to a newer PMD release with the required CPD language support rather than replacing the Bazel integration.
- Risk: a single cross-language CPD target may be awkward because PMD CPD expects one language per invocation. Mitigation: define separate CPD targets per language/source set and add all to `//:ci`.
- Risk: moving shared TypeScript polyfills across Bazel packages can break esbuild input resolution. Mitigation: keep package-local `linter/src/polyfills.ts` and `layout/src/polyfills.ts` as stable injection entry points and only delegate their implementation to a shared helper included in each bundle’s `srcs`.
- Risk: extracting Kotlin test fixtures can accidentally change test data names or owner maps. Mitigation: give the shared helper explicit defaults matching the current copied definitions, keep override parameters for tests that vary process IDs/names, and run affected JUnit tests after extraction.
- Risk: CPD may flag generated TypeScript or generated JSON if included. Mitigation: expose CPD source filegroups over authored source constants only and keep generated targets such as `:static_rules` and `:linter_rules_json_for_ts` out of CPD inputs.