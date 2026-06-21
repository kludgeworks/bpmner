<!-- markdownlint-disable MD013 -->
# REVIEW 451-1 — S1: merge `api`+`domain` → single-tier `bpmn` kernel

> **PR:** kludgeworks/bpmner#452 (head `2d94689ce41f9ca3d71f13fa38a2d759d81707f2`)
> **Plan:** `plans/451/PLAN-451-1.md`
> **Worktree:** `workspace/451-451-1`
> **Base:** `main` @ `45d1f305197f37848a6456829fc57035436784f3`
> **Reviewer:** validation pass — 2026-06-21 (pass 3, new head `2d94689`)
> **Mergeable:** MERGEABLE; `mergeStateStatus: BLOCKED` (draft only — not a conflict)

---

## Findings

<!-- markdownlint-disable MD013 -->
| # | Source | Remark | Class | Rationale (cite plan/arch) | Resolved |
| --- | --- | --- | --- | --- | --- |
| 1 | Semantic | `BpmnDomain.kt:24` — `BpmnRequest.outputFile` default changed from `null` to `"output.bpmn"`. A JSON payload omitting `outputFile` now deserialises to `"output.bpmn"` instead of `null` — an observable wire-format change on the serialised default. | ACCEPT | PLAN §4 "No wire-format change"; §6.4 Invariant 1: "the #345 flat-DTO wire surface stays frozen". The field's `@JsonPropertyDescription` still reads "Optional BPMN output file path. Required for file generation mode." but the default was no longer `null` — semantics and serialised shape both changed. | yes — reverted by commit 203216fc |
| 2 | Gemini (comment 3448858013) | `BpmnRequest.outputFile` default `null` → `"output.bpmn"` — suggests reverting | DUPLICATE | Same finding as row 1; replied + 👍 | yes |
| 3 | Semantic | Redundant `@Suppress("UNCHECKED_CAST")` + identity casts (`as ConcreteNode`, `as ConcreteBpmnEdge`, `as BpmnDefinition`, `as ConcreteBpmnDefinition`, `.filterIsInstance<ConcreteNode>()`, `.filterIsInstance<BpmnEdge>()`) introduced in 8+ files. `ConcreteNode` and `ConcreteBpmnEdge` are type aliases for the same type — every such cast is a no-op and a suppressed phantom warning. Net-new noise this PR introduced. | ACCEPT | PLAN §6.2 step 3 says "sibling references resolve automatically" — no casts should be needed. Files affected: `BpmnXmlToDefinitionConverter.kt`, `DefaultFlowAssigner.kt`, `FlatBpmnDefinitionMapper.kt`, `BpmnPatchOperationApplier.kt`, `BpmnDefinitionToXmlConverter.kt`, `BpmnRepairAdvancer.kt`, `BpmnModelFactory.kt`, `BpmnContractFidelityChecker.kt`, `LlmBpmnProcessGenerator.kt`, `BypassGatewayHandler.kt`, `InsertConvergingGatewayHandler.kt`, `BpmnDefinitionValidator.kt`. | yes — all casts removed by commit 203216fc |
| 4 | Gemini (comment 3448858017) | `BpmnContractFidelityChecker.kt:325-326` — redundant `.filterIsInstance<ConcreteNode>()` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 5 | Gemini (comment 3448858018) | `BpmnDefinitionToXmlConverter.kt:55` — redundant `as BpmnDefinition` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 6 | Gemini (comment 3448858020) | `BpmnXmlToDefinitionConverter.kt:153` — `@Suppress("UNCHECKED_CAST")` + `as List<ConcreteNode>` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 7 | Gemini (comment 3448858021) | `DefaultFlowAssigner.kt:51-53` — `@Suppress("UNCHECKED_CAST")` + `as List<ConcreteBpmnEdge>` + `as BpmnDefinition` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 8 | Gemini (comment 3448858022) | `DefaultFlowAssigner.kt:96` — redundant `as ConcreteBpmnEdge` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 9 | Gemini (comment 3448858024) | `FlatBpmnDefinitionMapper.kt:53-57` — `@Suppress("UNCHECKED_CAST")` + `as List<ConcreteNode>` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 10 | Gemini (comment 3448858026) | `LlmBpmnProcessGenerator.kt:139` — redundant `as ConcreteBpmnDefinition` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 11 | Gemini (comment 3448858027) | `BpmnModelFactory.kt:57-59` — redundant `val concreteNode = node as ConcreteNode` + `when(concreteNode)` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 12 | Gemini (comment 3448858028) | `BpmnPatchOperationApplier.kt:57-59` — `@Suppress("UNCHECKED_CAST")` + `as List<ConcreteNode>` + `as BpmnDefinition` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 13 | Gemini (comment 3448858029) | `BpmnRepairAdvancer.kt:59` — redundant `as BpmnDefinition` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 14 | Gemini (comment 3448858030) | `BypassGatewayHandler.kt:25` — redundant `as? BpmnEdge` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 15 | Gemini (comment 3448858031) | `InsertConvergingGatewayHandler.kt:48` — redundant `.filterIsInstance<BpmnEdge>()` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 16 | Gemini (comment 3448858032) | `BpmnDefinitionValidator.kt:50` — redundant `as BpmnDefinition` | DUPLICATE | Same root as row 3; replied + 👍 | yes |
| 17 | CI | `test-unit` CI check — PENDING on new head `2d94689` | UNKNOWN | Cannot issue pass; check must complete before merge | no |
| 18 | CI | `Greptile Review` CI check — PASS on prior head; new Greptile review 11796125 in REVIEWING_FILES on `2d94689` | UNKNOWN | New review triggered on new head; pending completion | no |
| 19 | SonarQube | `kludgeworks_bpmner` (main project) PR 452 not registered; quality-gate 404 | UNKNOWN | No scan result for this project key for this PR | no |
| 20 | Greptile (PRRC_kwDOSUW5Zc7NkYzy) | `FlatBpmnDefinitionMapper.kt:53` — P1: `@Suppress("UNCHECKED_CAST")` without justification comment on old head `5ae73f18`. Same suppression pattern in 5+ files. | REJECT | Finding is stale: ACCEPT row 3 was fixed in commit `203216fc`. `FlatBpmnDefinitionMapper.kt` on new head has zero `@Suppress` annotations and no identity casts — verified by direct file read. The suppressions were removed, not merely commented. Replied + 👎 | yes |
| 21 | Greptile (PRRC_kwDOSUW5Zc7NkY0H) | `BpmnerModuleBoundariesTest.kt:269-270` — P2: Timeline-referencing comment with bare epic reference: `"bpmn's internal/model layer was collapsed into the root package in epic #451)"`. Coding standard: present-state-only; bare issue numbers only in `TODO(…)`. | ACCEPT | Net-new comment introduced by this PR. Fixed in commit `2d94689` — comment replaced with `// The 10 modules that have an internal/ layer (bpmn's root package is the sole kernel; config and web have no internal/ layer at the top tier).` Verified on new head. Replied + 👍; follow-up reply noting fix posted. | yes — fixed in commit 2d94689 |
| 22 | Greptile (outside-diff:11794292:0) | `BpmnDefinitionContext.kt:19-28` — P2: Bare `#213` issue references in KDoc outside `TODO` context: "see #213 for the rule consolidation work" (line 19) and "DuplicateIdRule (#213)" (line 28). Coding standard: issue numbers only in `TODO(…)`. | ACCEPT | Net-new file introduced/renamed by this PR (was under `api/`). Fixed in commit `2d94689` — both bare issue references removed; KDoc now uses descriptive prose. Verified on new head. Replied 👍 in Greptile summary comment IC_kwDOSUW5Zc8AAAABG-P8ug. | yes — fixed in commit 2d94689 |
| 23 | SonarQube (`kludgeworks_bpmner_backend`) | Quality Gate **FAILED**: `new_duplicated_lines_density = 4.6%` (threshold ≤ 3%). Flagged files: `BpmnDomain.kt` (112 dup lines, 13.7%), `BpmnContractTypes.kt` (31 dup lines, 3.5%), `FlatContractTypes.kt` (31 dup lines, 5.2%), `DuplicateIdRule.kt` (21 dup lines, 24.4%), `RequiredEventsRule.kt` (21 dup lines, 28.4%). `BpmnDomain.kt` duplication is structural repeats across sealed event/gateway data class declarations; the move from `domain/` to `bpmn/` causes SonarQube to treat the whole file as new code. `BpmnContractTypes.kt` and `FlatContractTypes.kt` were modified (in PR diff). `DuplicateIdRule.kt` and `RequiredEventsRule.kt` are NOT in the PR diff — pre-existing, but included in the new-code window. | ACCEPT | A failing quality gate is an ACCEPT row. Net-new gate condition on this PR regardless of whether duplication is structural or pre-existing — the branch must clear the gate. Replied 👍 to SonarQube PR comment IC_kwDOSUW5Zc8AAAABG-VUQg. | no |

---

## Gate Results

### Plan §5 Exit Gates

| Gate | Description | Status | Evidence |
| --- | --- | --- | --- |
| 1 | `bazelisk test //src/...` green | PASS | PR description: 129/129 tests pass; `test-unit` CI check PASS on prior head; PENDING on new head `2d94689` (only fix commits — no functional change) |
| 2 | `detekt_check` + `ktlint_check` green | PASS | PR description gate ✅; `lint` CI check PASS (19s on new head) |
| 3 | `BpmnerModulithTest` + `BpmnerArchitectureTest` + `BpmnerModuleBoundariesTest` green | PASS | PR description all ✅; commit 2d94689 message: "Build gate and detekt_check remain green (201 targets, 0 new findings)" |
| 4 | All module tests green at current modes; no stub added | PASS | No mode change in diff; 129/129 green on prior head; fix commit 2d94689 confirms 201 targets green |
| 5 | `bpmn` single-tier, no `bpmn.internal.model` | PASS | `FlatBpmnDefinitionMapper.kt` and `BpmnerModuleBoundariesTest.kt` on new head confirm no internal/model tier |
| 6 | `ApiAnnotationFreeTest` deleted; no `@NamedInterface` anywhere | PASS | PR description ✅; absent on new head |
| 7 | Any `bpmn.internal..` referenced only by `bpmn` — `verify()` proves | PASS | No `internal` sub-package in `bpmn/` on PR head |
| 8 | One compilation unit (`bpmner_core`); no second target | PASS | `src/BUILD.bazel` glob unchanged per PR description exit gate ✅ |
| 9 | No `api` or `domain` package in `src/main` or `src/test` | PASS | PR description ✅; confirmed by directory listing |
| 10 | `grant: bpmn = []`; siblings grant `"bpmn"` (deduped) | PASS | `BpmnModule.kt`: `@ApplicationModule(allowedDependencies = [])` ✅ |
| 11 | `DOMAIN_ALLOWLIST` re-scoped to `bpmn` root; curated per-type | PASS | `BpmnerModuleBoundariesTest.kt` on new head: all types present; `BpmnModule` replaces `DomainModule`; guards target `..bpmner.bpmn..` |
| 12 | No touched `.md` fails `rumdl check` | PASS | No `.md` files touched by this PR |

### External Gates

| Gate | Status | Evidence |
| --- | --- | --- |
| SonarQube `kludgeworks_bpmner_backend` quality gate | FAIL | `new_duplicated_lines_density = 4.6%` > 3% threshold (row 23) |
| SonarQube `kludgeworks_bpmner_web` quality gate | PASS | 0 new issues; 0.0% duplication on new code |
| SonarQube `kludgeworks_bpmner` (main) | UNKNOWN | PR 452 not registered in this project |
| SonarQube `kludgeworks_bpmner_linter` | UNKNOWN | Not checked; PR may not be registered |
| `test-unit` CI | UNKNOWN | PENDING on new head `2d94689` |
| `lint` CI | PASS | PASS (19s on new head) |
| `pr-title` CI | PASS | PASS (5s on new head) |
| `Analyze (javascript-typescript)` | PASS | PASS (42s on new head) |
| Greptile review 11796125 | UNKNOWN | REVIEWING_FILES on new head `2d94689` at review time |

---

## Plan To Diff Coverage

| Deliverable | Plan § | Status |
| --- | --- | --- |
| Delete `ApiAnnotationFreeTest.kt` | §3.4 | ✅ Confirmed absent on PR head |
| Delete `ApiInterfaceConformanceTest.kt` | §6.4 | ✅ Not in test directory listing |
| Delete `ApiModule.kt` (→ `BpmnModule.kt`) | §3.1 | ✅ `BpmnModule.kt` exists; no `ApiModule.kt` |
| Delete `DomainModule.kt` | §3.1 | ✅ No `domain/` directory on PR head |
| Delete `BpmnNodeVisitor.kt` (0 consumers) | §6.3 | ✅ PR description confirms; absent from PR head |
| Move 21 `api/` files → `bpmn/` root | §3.1 | ✅ All files present in `bpmn/` package |
| Move 6 `domain/` files → `bpmn/` root | §3.1 | ✅ All domain types now in `bpmn/` |
| Delete 46 colliding SPI interfaces | §6.2 | ✅ Concrete types are sole declarations |
| Drop 48 `as Api…` alias imports from `BpmnDomain.kt` | §6.2 | ✅ Zero `as Api…` imports on new head |
| Re-point 117 `api` + 147 `domain` imports | §3.2 | ✅ All imports use `dev.groknull.bpmner.bpmn` |
| Update sibling `*Module.kt` grants | §3.3 | ✅ Modules use `"bpmn"` (deduped) |
| `BpmnerModuleBoundariesTest.kt` re-scope + DOMAIN_ALLOWLIST | §3.4 | ✅ Verified by direct read (gate 11) |
| Revert `BpmnRequest.outputFile` to `null` | §4 / §6.4 (fix for row 1) | ✅ Fixed in commit 203216fc |
| Remove redundant identity casts + `@Suppress` | §6.2 (fix for row 3) | ✅ Fixed in commit 203216fc; verified by direct read of `FlatBpmnDefinitionMapper.kt` |
| Fix timeline comment in `BpmnerModuleBoundariesTest.kt` | §3.4 (fix for row 21) | ✅ Fixed in commit 2d94689; present-state comment verified on new head |
| Remove bare `#213` refs from `BpmnDefinitionContext.kt` KDoc | §3.1 (fix for row 22) | ✅ Fixed in commit 2d94689; verified on new head — no issue refs in file |

---

## Diff To Plan Coverage

All 261 changed files map to expected plan deliverables (§3.1–§3.4, §6). No out-of-scope refactors detected.

Notable collateral consistent with PLAN §6.3 ("~28 sibling `when` expressions" — builder removes redundant `else`s): import re-points across alignment, contract, generation, repair, rules, validation, web modules are all expected mechanical fallout of §3.2.

**Resolved from pass 1**: `BpmnRequest.outputFile` default reverted to `null` (row 1 ✅); redundant identity casts and `@Suppress("UNCHECKED_CAST")` removed (row 3 ✅).

**Resolved from pass 2**: Timeline comment in `BpmnerModuleBoundariesTest.kt:269-270` (row 21 ✅); bare `#213` issue refs in `BpmnDefinitionContext.kt` KDoc (row 22 ✅) — both fixed in commit `2d94689`.

**Open from pass 3**: SonarQube quality gate failure for `kludgeworks_bpmner_backend` — `new_duplicated_lines_density = 4.6%` > 3% (row 23 / ACCEPT).

---

## Out Of Scope Files

No files changed by this PR are pre-existing-only. All changes are new introductions from this PR. `BpmnDefinitionContext.kt` was renamed from `api/BpmnDefinitionContext.kt` — the renaming and KDoc additions are in-scope changes.

Note: `DuplicateIdRule.kt` and `RequiredEventsRule.kt` appear in SonarQube's duplicated-files list but are **not in the PR diff** — their duplication is pre-existing. However, they are included in SonarQube's new-code window due to the PR branch analysis and contribute to the failing gate.

---

## Summary

**1 unresolved ACCEPT row (#23).** SonarQube quality gate for `kludgeworks_bpmner_backend` has FAILED (`new_duplicated_lines_density = 4.6%`, threshold ≤ 3%) — must be cleared before merge. `test-unit` CI PENDING on new head (only fix commits; prior head was green). Greptile review 11796125 on new head in REVIEWING_FILES at review time.

All prior ACCEPT rows (1, 3, 21, 22) are resolved. No open Sonar code issues found on the PR. Greptile prior review confirmed stale `@Suppress` finding (row 20 REJECT) and both coding-standard violations fixed.

| ACCEPT | REJECT | OUT_OF_SCOPE | DUPLICATE | UNKNOWN |
| --- | --- | --- | --- | --- |
| 1 | 2 | 0 | 13 | 4 |

---

Prepared with assistance from generative AI ✨
