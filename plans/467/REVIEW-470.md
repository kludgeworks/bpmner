<!-- markdownlint-disable MD013 -->
# Review — Epic 467 / Sub-issue 470

**PR:** #481 `feat/467-470` → `main`
**Head SHA:** `19fada008139426d024b303929ec7d1381b92799`
**Plan:** `plans/467/PLAN-470.md`
**Worktree:** `workspace/467-470`
**Reviewer:** claude-sonnet-4-6
**Date:** 2026-06-25

## Summary

Stage 3 of epic #467: evict `BpmnAlignmentConfig`/`BpmnAlignmentThresholdsConfig` to
`alignment/internal`, `BpmnContractConfig`/`BpmnContractThresholdsConfig` to
`contract/internal`, and `DeepSeekModelsConfig`/`OpenRouterModelsConfig`
(+ `*Properties`) to `llm/internal`.

13 files changed (40 additions / 40 deletions): 3 renamed config files, 2
in-module importer fixups, 8 test import/visibility fixups.

## Findings

| # | Source | Remark | Class | Rationale (cite plan/arch) | Resolved |
| --- | --- | --- | --- | --- | --- |
| 1 | Semantic | `alignment` root retains exactly: `AlignmentClassification.kt`, `BpmnAligner.kt`, `BpmnAlignmentCheckedEvent.kt`, `BpmnAlignmentTypes.kt`, `AlignmentModule.kt` — confirmed via GitHub API on HEAD | REJECT | PLAN-470.md §A exit gate — directory contents verified against HEAD | yes |
| 2 | Semantic | `contract` root retains exactly: `ContractModule.kt`, `BpmnContractTypes.kt`, `ProcessContractExtractor.kt`, `BpmnContractDiagnostics.kt`, `ProcessContractMarkdownRenderer.kt` — confirmed | REJECT | PLAN-470.md §B exit gate + ADR 467-3 — verified | yes |
| 3 | Semantic | `llm` root retains only `LlmModule.kt` — confirmed | REJECT | PLAN-470.md §C exit gate — verified | yes |
| 4 | Semantic | `BpmnAlignmentConfig` and `BpmnAlignmentThresholdsConfig` declared `internal` — diff confirms both declarations prefixed `internal` | REJECT | PLAN-470.md §A — verified in diff | yes |
| 5 | Semantic | `BpmnContractConfig` and `BpmnContractThresholdsConfig` declared `internal` — verified in diff | REJECT | PLAN-470.md §B — verified in diff | yes |
| 6 | Semantic | `DeepSeekProperties`, `DeepSeekModelsConfig`, `OpenRouterProperties`, `OpenRouterModelsConfig` all declared `internal` — verified in diff | REJECT | PLAN-470.md §C — verified in diff | yes |
| 7 | Semantic | `BpmnAlignmentConfig.kt` renamed from `alignment/` root → `alignment/internal/`, package updated to `dev.groknull.bpmner.alignment.internal` | REJECT | PLAN-470.md §A — diff status `renamed`, package line confirmed | yes |
| 8 | Semantic | `BpmnContractConfig.kt` renamed from `contract/` root → `contract/internal/`, package updated to `dev.groknull.bpmner.contract.internal` | REJECT | PLAN-470.md §B — diff status `renamed`, package line confirmed | yes |
| 9 | Semantic | `DeepSeekModelsConfig.kt` and `OpenRouterModelsConfig.kt` renamed from `llm/` root → `llm/internal/`, packages updated to `dev.groknull.bpmner.llm.internal` | REJECT | PLAN-470.md §C — both diff entries show `renamed` status | yes |
| 10 | Semantic | `llm/internal/` directory created (was non-existent per plan pre-flight) | REJECT | PLAN-470.md §C pre-flight — GitHub API confirms dir exists at HEAD | yes |
| 11 | Semantic | `LlmBpmnAligner.kt` import fixed: `alignment.BpmnAlignmentConfig` → `alignment.internal.BpmnAlignmentConfig` | REJECT | PLAN-470.md §A — verified in diff | yes |
| 12 | Semantic | `BpmnAlignmentPostChecker.kt` import fixed: `alignment.BpmnAlignmentThresholdsConfig` → `alignment.internal.BpmnAlignmentThresholdsConfig` | REJECT | PLAN-470.md §A — verified in diff | yes |
| 13 | Semantic | `LlmProcessContractExtractor.kt` imports fixed: both contract config types updated to `contract.internal` | REJECT | PLAN-470.md §B — verified in diff (2 import lines) | yes |
| 14 | Semantic | Test imports fixed: `BpmnConfigCachingTest.kt` (alignment + contract), `BpmnConfigBindingTest.kt` (alignment + contract), `BpmnConfigThresholdBindingTest.kt` (alignment + contract), `BpmnAlignmentPostCheckerTest.kt` | REJECT | PLAN-470.md §A/§B test import list — all 4 files appear in diff with correct updated imports | yes |
| 15 | Semantic | Test imports fixed: `PromptFixtures.kt` and `ExtractContractTemplateTest.kt` (contract) | REJECT | PLAN-470.md §B — both appear in diff with correct updated imports | yes |
| 16 | Semantic | `BpmnContractDiagnostics.kt` NOT moved — stays in `contract` root | REJECT | ADR 467-3 §1; ARCHITECTURE.md line 98; PLAN-470.md non-goals — file absent from diff, confirmed present in contract root at HEAD | yes |
| 17 | Semantic | `ProcessContractMarkdownRenderer.kt` NOT moved — stays in `contract` root per ADR 467-3 §2 | REJECT | ARCHITECTURE.md line 99; PLAN-470.md non-goals — file absent from diff, confirmed present in contract root at HEAD | yes |
| 18 | Semantic | No files outside `alignment`/`contract`/`llm` modules in the diff — non-goal respected | REJECT | PLAN-470.md non-goals — all 13 diff entries are inside the three modules (test config files live in `config`/`prompt`/`prompts` test packages, which are compilation-module peers, not separate production modules) | yes |
| 19 | Semantic | `@Autowired` test fields for non-internal types also marked `internal` in `BpmnConfigBindingTest.kt` and `BpmnConfigThresholdBindingTest.kt` (e.g. `readinessConfig`, `loggingConfig`, `conformanceConfig`) | REJECT | Types are public on HEAD; making the test field `internal` is harmless and defensive for future stages. Test code only; no production impact. The plan's non-goal covers "visibility of any **type**" (§ non-goals last bullet) — these are field access modifiers in test classes, not type declarations. Kotlin accepts this; detekt passes per PR description. | yes |
| 20 | CI | `test-unit` CI check pending at review time (run ID 28194885813) | UNKNOWN | Required check not yet complete — cannot pass until green | no |
| 21 | CI | `Greptile Review` CI check pending at review time (check run 83519284201) | UNKNOWN | Greptile review still `REVIEWING_FILES`; cannot pass until complete | no |
| 22 | SonarQube | SonarQube PR #481: 0 open issues on `kludgeworks_bpmner` | REJECT | No new OPEN issues of any severity; quality gate status returned 404 (PR not yet scanned — not a gate failure, no scan result exists) | yes |
| 23 | Greptile | No Greptile inline comments on PR #481 (first run SKIPPED; second run in progress at closeout time) | REJECT | Zero Greptile comments to triage; nothing to fold into table | yes |
| 24 | Merge | PR `mergeable: true`, `mergeable_state: behind` — merges cleanly with `main`, but branch is behind `main` by 1 commit (the REVIEW skeleton push) | UNKNOWN | `behind` is not a conflict: the branch needs a base update before merging but is otherwise clean. Base update is a merge operation for the human. | no |

## Gate Results

| Gate | Status | Evidence |
|------|--------|----------|
| Alignment root retains exactly 5 files | PASS | GitHub API on HEAD lists exactly: AlignmentClassification.kt, BpmnAligner.kt, BpmnAlignmentCheckedEvent.kt, BpmnAlignmentTypes.kt, AlignmentModule.kt |
| Contract root retains exactly 5 files | PASS | GitHub API on HEAD lists exactly: ContractModule.kt, BpmnContractTypes.kt, ProcessContractExtractor.kt, BpmnContractDiagnostics.kt, ProcessContractMarkdownRenderer.kt |
| LLM root retains exactly LlmModule.kt | PASS | GitHub API on HEAD confirms single file |
| All 6 config/properties classes declared `internal` | PASS | All 6 declarations confirmed in diff |
| No production-module cross-boundary import of moved types | UNKNOWN | Requires CI (BpmnerModulithTest); PR reports `bazel test //:detekt_check` ✅ but CI test-unit pending |
| `hk check` passes | PASS | PR body reports `hk check` ✅; `lint` CI check PASS |
| All in-module importer fixups applied | PASS | 5 import lines updated across 3 production files — verified in diff |
| All test import/visibility fixups applied | PASS | 8 test files updated — verified in diff |

## Plan To Diff Coverage

All 14 plan requirements covered. See rows 1–19 above for per-requirement evidence.

| Plan Requirement | Coverage |
|---|---|
| §A: Move + package + internal BpmnAlignmentConfig | COVERED |
| §A: Fix LlmBpmnAligner.kt import | COVERED |
| §A: Fix BpmnAlignmentPostChecker.kt import | COVERED |
| §A: Fix 4 test imports (alignment) | COVERED |
| §A: Keep alignment root files | COVERED |
| §B: Move + package + internal BpmnContractConfig | COVERED |
| §B: Fix LlmProcessContractExtractor.kt imports | COVERED |
| §B: Fix 5 test imports (contract) | COVERED |
| §B: Keep BpmnContractDiagnostics + ProcessContractMarkdownRenderer in root | COVERED |
| §B: Keep contract root files | COVERED |
| §C: Create llm/internal/ | COVERED |
| §C: Move + package + internal DeepSeekModelsConfig + OpenRouterModelsConfig | COVERED |
| §C: Keep LlmModule.kt in root | COVERED |
| Non-goals: no files outside alignment/contract/llm | COVERED |

## Diff To Plan Coverage

All 13 diff files are IN_PLAN. No unexpected files.

| File | Status |
|---|---|
| alignment/internal/BpmnAlignmentConfig.kt (renamed) | IN_PLAN §A |
| alignment/internal/adapter/inbound/LlmBpmnAligner.kt | IN_PLAN §A |
| alignment/internal/domain/BpmnAlignmentPostChecker.kt | IN_PLAN §A |
| contract/internal/BpmnContractConfig.kt (renamed) | IN_PLAN §B |
| contract/internal/adapter/inbound/LlmProcessContractExtractor.kt | IN_PLAN §B |
| llm/internal/DeepSeekModelsConfig.kt (renamed) | IN_PLAN §C |
| llm/internal/OpenRouterModelsConfig.kt (renamed) | IN_PLAN §C |
| test/.../alignment/internal/domain/BpmnAlignmentPostCheckerTest.kt | IN_PLAN §A |
| test/.../config/BpmnConfigBindingTest.kt | IN_PLAN §A/§B |
| test/.../config/BpmnConfigCachingTest.kt | IN_PLAN §A/§B |
| test/.../config/BpmnConfigThresholdBindingTest.kt | IN_PLAN §A/§B |
| test/.../prompt/PromptFixtures.kt | IN_PLAN §B |
| test/.../prompts/ExtractContractTemplateTest.kt | IN_PLAN §B |

## Out Of Scope Files

None. All files are within alignment/contract/llm modules (main sources) or their
corresponding test compilation units (shared compilation module). No files from
authoring/bpmn/conformance/readiness/ruleset/repair production sources were modified.
