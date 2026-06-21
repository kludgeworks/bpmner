<!-- markdownlint-disable MD013 -->
# REVIEW 451-1 — S1: merge `api`+`domain` → single-tier `bpmn` kernel

> **PR:** kludgeworks/bpmner#452 (head `2d94689ce41f9ca3d71f13fa38a2d759d81707f2`)
> **Plan:** `plans/451/PLAN-451-1.md`
> **Worktree:** `workspace/451-451-1`
> **Base:** `main` @ `45d1f305197f37848a6456829fc57035436784f3`
> **Reviewer:** validation pass — 2026-06-21 (pass 4 — final)
> **Mergeable:** `mergeable: true`; `mergeable_state: behind` (branch is behind main due to REVIEW file commits; no content conflict — `plans/` not touched by PR branch)
> **Review status:** CLEAN — all findings resolved or overridden; PR is ready for review (not draft)

---

## Findings

<!-- markdownlint-disable MD013 -->
| # | Source | Remark | Class | Rationale (cite plan/arch) | Resolved |
| --- | --- | --- | --- | --- | --- |
| 1 | Semantic | `BpmnDomain.kt:24` — `BpmnRequest.outputFile` default changed from `null` to `"output.bpmn"`. A JSON payload omitting `outputFile` now deserialises to `"output.bpmn"` instead of `null` — an observable wire-format change on the serialised default. | ACCEPT | PLAN §4 "No wire-format change"; §6.4 Invariant 1: "the #345 flat-DTO wire surface stays frozen". | yes — reverted by commit 203216fc |
| 2 | Gemini (comment 3448858013) | `BpmnRequest.outputFile` default `null` → `"output.bpmn"` — suggests reverting | DUPLICATE | Same finding as row 1; replied + 👍 | yes |
| 3 | Semantic | Redundant `@Suppress("UNCHECKED_CAST")` + identity casts introduced in 12 files (`as ConcreteNode`, `as ConcreteBpmnEdge`, `as BpmnDefinition`, `as ConcreteBpmnDefinition`, `.filterIsInstance<ConcreteNode>()`, `.filterIsInstance<BpmnEdge>()`). `ConcreteNode` / `ConcreteBpmnEdge` are type aliases for the same type — every cast is a no-op and a suppressed phantom warning. | ACCEPT | PLAN §6.2 step 3 says "sibling references resolve automatically" — no casts needed. | yes — all casts removed by commit 203216fc |
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
| 17 | CI | `test-unit` — was PENDING at pass 3; now PASS on run 27915177934 (1m14s). | REJECT | Pass confirmed; check complete. | yes |
| 18 | CI | Greptile review 11796125 — was REVIEWING_FILES at pass 3; now COMPLETED on `2d94689` with 0 comments published (2 P2 candidates filtered by round-2 gate). | REJECT | No new findings; clean on new head. | yes |
| 19 | SonarQube | `kludgeworks_bpmner` (main project) PR 452 not registered; quality-gate 404. | UNKNOWN | Project scan did not register this PR branch. No issues found. | no — no action required |
| 20 | Greptile (PRRC_kwDOSUW5Zc7NkYzy) | `FlatBpmnDefinitionMapper.kt:53` — P1: stale `@Suppress("UNCHECKED_CAST")` finding on old head `5ae73f18`. | REJECT | Stale: casts removed in commit `203216fc`. Replied + 👎 | yes |
| 21 | Greptile (PRRC_kwDOSUW5Zc7NkY0H) | `BpmnerModuleBoundariesTest.kt:269-270` — P2: Timeline comment with bare epic ref. | ACCEPT | Fixed in commit `2d94689`. Thread resolved; follow-up reply posted. | yes — fixed in commit 2d94689 |
| 22 | Greptile (outside-diff:11794292:0) | `BpmnDefinitionContext.kt:19-28` — P2: Bare `#213` issue refs in KDoc. | ACCEPT | Fixed in commit `2d94689`. Replied 👍. | yes — fixed in commit 2d94689 |
| 23 | SonarQube (`kludgeworks_bpmner_backend`) | Quality Gate FAILED: `new_duplicated_lines_density = 4.6%` (threshold ≤ 3%). `[bpmner Backend] SonarCloud Code Analysis` CI check: **fail**. | REJECT | **Human override** (2026-06-21): duplication is structural (sealed BPMN type hierarchy shape); not addressable within S1 scope (PLAN §4 non-goals). Row closed. | yes — human override |

---

## Gate Results

### Plan §5 Exit Gates

| Gate | Description | Status | Evidence |
| --- | --- | --- | --- |
| 1 | `bazelisk test //src/...` green | PASS | `test-unit` CI PASS (1m14s, run 27915177934); PR description 129/129 ✅ |
| 2 | `detekt_check` + `ktlint_check` green | PASS | `lint` CI PASS (1m48s, run 27915177934) ✅ |
| 3 | `BpmnerModulithTest` + `BpmnerArchitectureTest` + `BpmnerModuleBoundariesTest` green | PASS | PR description ✅; `test-unit` CI PASS confirms; commit `2d94689` "201 targets green" |
| 4 | All module tests green at current modes; no stub added | PASS | 129/129 green; no mode change; no stub |
| 5 | `bpmn` single-tier, no `bpmn.internal.model` | PASS | Verified on head `2d94689` |
| 6 | `ApiAnnotationFreeTest` deleted; no `@NamedInterface` anywhere | PASS | PR description ✅; absent on new head |
| 7 | Any `bpmn.internal..` referenced only by `bpmn` | PASS | No `internal` sub-package in `bpmn/` |
| 8 | One compilation unit (`bpmner_core`); no second target | PASS | `src/BUILD.bazel` glob unchanged ✅ |
| 9 | No `api` or `domain` package in `src/main` or `src/test` | PASS | PR description ✅; confirmed by directory listing |
| 10 | `grant: bpmn = []`; siblings grant `"bpmn"` (deduped) | PASS | `BpmnModule.kt`: `@ApplicationModule(allowedDependencies = [])` ✅ |
| 11 | `DOMAIN_ALLOWLIST` re-scoped to `bpmn` root; curated per-type | PASS | `BpmnerModuleBoundariesTest.kt` verified on head `2d94689` |
| 12 | No touched `.md` fails `rumdl check` | PASS | No `.md` files touched by PR branch |

### External Gates (pass 4)

| Gate | Status | Evidence |
| --- | --- | --- |
| `test-unit` CI | **PASS** | run 27915177934, 1m14s |
| `lint` CI | **PASS** | run 27915177934, 1m48s |
| `pr-title` CI | **PASS** | |
| `Analyze (javascript-typescript)` CI | **PASS** | |
| `Greptile Review` CI | **PASS** | review 11796125 COMPLETED, 0 new findings |
| `SonarCloud` CI | **PASS** | |
| `[bpmner Web] SonarCloud Code Analysis` | **PASS** | 0 new issues |
| `[bpmner Backend] SonarCloud Code Analysis` | **FAIL → overridden** | row 23 REJECT (human override) |
| `SonarCloud Scan` CI | PENDING | live scan job still running; plan gates do not require it (offline test gate) |
| `smoke-tests (*)` CI | PENDING | live-LLM tests excluded per PLAN §5 gate 1 |
| Greptile review 11796125 | **COMPLETED** | 0 comments published on head `2d94689`; clean |
| `kludgeworks_bpmner_backend` quality gate | **FAIL → overridden** | row 23 REJECT (human override) |
| `kludgeworks_bpmner_web` quality gate | **PASS** | 0 new issues, 0% duplication |
| `kludgeworks_bpmner` (main) quality gate | UNKNOWN | PR not registered in this project |
| Merge cleanly with target | **YES** | `mergeable: true`; `mergeable_state: behind` — branch trails main by REVIEW file commits only (no content conflict; `plans/` not in PR diff) |
| All review conversations resolved | **YES** | 16/16 threads resolved (verified via GraphQL) |
| PR ready (not draft) | **YES** | `draft: false` |

---

## Plan To Diff Coverage

| Deliverable | Plan § | Status |
| --- | --- | --- |
| Delete `ApiAnnotationFreeTest.kt` | §3.4 | ✅ |
| Delete `ApiInterfaceConformanceTest.kt` | §6.4 | ✅ |
| Delete `ApiModule.kt` (→ `BpmnModule.kt`) | §3.1 | ✅ |
| Delete `DomainModule.kt` | §3.1 | ✅ |
| Delete `BpmnNodeVisitor.kt` (0 consumers) | §6.3 | ✅ |
| Move 21 `api/` files → `bpmn/` root | §3.1 | ✅ |
| Move 6 `domain/` files → `bpmn/` root | §3.1 | ✅ |
| Delete 46 colliding SPI interfaces | §6.2 | ✅ |
| Drop 48 `as Api…` alias imports from `BpmnDomain.kt` | §6.2 | ✅ |
| Re-point 117 `api` + 147 `domain` imports | §3.2 | ✅ |
| Update sibling `*Module.kt` grants | §3.3 | ✅ |
| `BpmnerModuleBoundariesTest.kt` re-scope + DOMAIN_ALLOWLIST | §3.4 | ✅ |
| Revert `BpmnRequest.outputFile` to `null` | §4 / §6.4 | ✅ commit 203216fc |
| Remove redundant identity casts + `@Suppress` | §6.2 | ✅ commit 203216fc |
| Fix timeline comment in `BpmnerModuleBoundariesTest.kt` | §3.4 | ✅ commit 2d94689 |
| Remove bare `#213` refs from `BpmnDefinitionContext.kt` KDoc | §3.1 | ✅ commit 2d94689 |

---

## Diff To Plan Coverage

All 261 changed files map to plan deliverables (§3.1–§3.4, §6). No out-of-scope refactors detected.

---

## Out Of Scope Files

None. `DuplicateIdRule.kt` and `RequiredEventsRule.kt` appeared in SonarQube's duplication window but are not in the PR diff — pre-existing, no action.

---

## Summary

**Review CLEAN.** All ACCEPT rows resolved. All plan exit gates PASS. CI green (test-unit, lint, pr-title, Analyze, Greptile, SonarCloud, bpmner-web). SonarCloud Backend gate failure human-overridden (row 23). Branch behind main by REVIEW commits only — no content conflict; `mergeable: true`. All 16 review threads resolved. PR not draft.

| ACCEPT | REJECT | OUT_OF_SCOPE | DUPLICATE | UNKNOWN |
| --- | --- | --- | --- | --- |
| 0 | 5 | 0 | 13 | 1 |

---

Prepared with assistance from generative AI ✨
