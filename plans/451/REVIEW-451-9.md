<!-- markdownlint-disable MD013 -->
# REVIEW 451-9 — S9: Kotlin-internal encapsulation audit

**PR:** kludgeworks/bpmner#466 — `refactor: S9 Kotlin-internal encapsulation audit — wall 4 root-package leaks (ADR-451-8)`
**Head SHA (pass 2):** `209007e55fde8cd3263e09895dc0dcd89e25093a` (commit 2 — L4 disposition-a complete)
**Head SHA (pass 1):** `52f67823c464e4c0fe7d988603ce8c8dc2ccb777`
**Base:** `main` @ `cb02d5d8e84ebb2287ce6fc7582b02c27edb12fc`
**Plan:** `plans/451/PLAN-451-9.md`
**Worktree:** `workspace/451-451-9`
**Reviewer:** claude-sonnet-4-6
**Date:** 2026-06-23 (pass 1 — /validate) / 2026-06-24 (pass 2 — /closeout)

---

## Summary

**Pass 2 (closeout, head `209007e`):** 18 files changed (+245 / -86). Second commit completes disposition-a for L4: `BpmnRequestResolver` extracted from `BpmnRequestDraft.kt` (authoring root) and added as new file `authoring/internal/domain/BpmnRequestResolver.kt`, implementing `BpmnRequestResolutionPort`. Old root-package class removed. ACCEPT row #5 from pass 1 is now resolved. New finding: stale `[BpmnContractFidelityChecker]` KDoc reference in `BpmnContractAwareValidator.kt:25–26` — field type changed to `BpmnContractFidelityPort` but the class-level KDoc description was not updated (row #21, ACCEPT).

**Pass 1 (pass 1, head `52f678`):** 16 files changed (+171 / -33). PR introduces 3 new `@PrimaryPort` interfaces at the `authoring` root (`BpmnContractFidelityPort`, `BpmnDefaultFlowPort`, `BpmnRequestResolutionPort`), relocates `BpmnContractFidelityChecker` and `DefaultFlowAssigner` to `authoring.internal.domain`, drops `internal` from `ProcessContractMarkdownRenderer` (L1 disposition-b), seals `BpmnRequestResolver` behind `BpmnRequestResolutionPort` (L4 — port added, but implementation not yet moved to `*.internal.*`), re-seams all 4 importers, updates `RepairModuleTest` KDoc (TODO removed, Tier-3 rationale settled), and adds an explanatory comment block to `BpmnerModuleBoundariesTest`.

---

## Findings

| # | Source | Remark | Class | Rationale (cite plan/arch) | Resolved |
| --- | --- | --- | --- | --- | --- |
| 1 | Semantic | **L1 ProcessContractMarkdownRenderer** — `internal` keyword dropped; class kept at `contract` root (disposition-b). Verified: `contract/ProcessContractMarkdownRenderer.kt:14` now `class ProcessContractMarkdownRenderer`. | REJECT | Correct disposition per PLAN-451-9 §3.1 and ADR-451-8 disposition-b. Renderer is a legitimate published capability of `contract`, reused by two importers. | yes |
| 2 | Semantic | **L2 BpmnContractFidelityChecker** — relocated to `authoring.internal.domain`, implements `BpmnContractFidelityPort`. `repair.BpmnContractAwareValidator` injects the port. Verified: package header, imports, implements clause, `override fun check`. | REJECT | Correct disposition per PLAN-451-9 §3.1 and ADR-451-8 disposition-a. Port at `authoring` root; implementation walled in `*.internal.*`; `verify()` mechanism-1 fires on any direct cross-module reach. | yes |
| 3 | Semantic | **L3 DefaultFlowAssigner** — relocated to `authoring.internal.domain`, implements `BpmnDefaultFlowPort`. `repair.BpmnRepairAdvancer` injects the port. Verified diff. | REJECT | Correct disposition per PLAN-451-9 §3.1 and ADR-451-8 disposition-a. Same pattern as L2. | yes |
| 4 | Semantic | **L4 BpmnRequestResolver — port added correctly** — `BpmnRequestResolutionPort` added at `authoring` root; `BpmnRequestResolver` implements it; `pipeline.BpmnGenerationAgent` injects the port. Port blocks the direct cross-module import from `pipeline`. | REJECT | Port seam is correct per PLAN-451-9 §4.1 L4. KDoc in the port records why the implementation stays `internal`. | yes |
| 5 | Semantic | **L4 BpmnRequestResolver — implementation relocated to `authoring.internal.domain`** — Pass 1: implementation stayed at `authoring` root as `internal class` in `BpmnRequestDraft.kt:40`. Pass 2: extracted to new file `authoring/internal/domain/BpmnRequestResolver.kt`; `BpmnRequestDraft.kt` now contains only the data class. Verified: `package dev.groknull.bpmner.authoring.internal.domain` at line 1; `internal class BpmnRequestResolver : BpmnRequestResolutionPort`. `BpmnRequestDraft.kt` no longer contains `BpmnRequestResolver`. `verify()` (mechanism-1) now enforces the `*.internal.*` path boundary for L4. | ACCEPT | **RESOLVED in pass 2.** PLAN §4.1 option (a) complete: implementation relocated to `authoring.internal.*`; port already present; `pipeline` already re-seamed behind port. Mechanism-1 now closes the L4 gap. | yes |
| 6 | Semantic | **Test files import concrete `internal` implementations directly** — `BpmnContractFidelityCheckerTest.kt` and `DefaultFlowAssignerTest.kt` import `dev.groknull.bpmner.authoring.internal.domain.BpmnContractFidelityChecker`. Tests are in `dev.groknull.bpmner.authoring` (same module). Pass 2: `BpmnRequestResolverTest.kt` also imports `authoring.internal.domain.BpmnRequestResolver`. | REJECT | Per PLAN-451-9 §4.3: "in-module unit tests may keep direct construction." In-module reference is legal. Correct pattern. | yes |
| 7 | Semantic | **`BpmnComposeGraphTest` mocks ports instead of concrete classes** — now mocks `BpmnContractFidelityPort`/`BpmnDefaultFlowPort` (interfaces). Test in `authoring.internal.adapter.inbound` (same module). | REJECT | Using the port interface in mocks is preferred for test stability. Correct per PLAN §4.3. | yes |
| 8 | Semantic | **`RepairModuleTest` KDoc + `TODO(#451-9)` removed** — Settled Tier-3 rationale: `alignment` not in `repair`'s `allowedDependencies`; wiring `authoring` fully under `DIRECT_DEPENDENCIES` impossible. TODO removed; KDoc is present-tense and accurate. | REJECT | Correct per ADR-451-9 Tier-3 rationale and PLAN §7 exit gate 2. | yes |
| 9 | Semantic | **Boundary guard: `BpmnerModuleBoundariesTest` comment block** — 21-line block explains S9 dispositions and records ArchUnit `internal`-keyword limitation (Kotlin compiles it to package-private JVM with name mangling, not a queryable flag). No new executable test added. | REJECT | Per PLAN-451-9 §4.4 (best-effort, lines 921–923) and §7 exit gate 4. Documented limitation is technically accurate. Structural re-seam (mechanism 1) is the correct ongoing posture. | yes |
| 10 | Semantic | **Architecture ledger (ARCHITECTURE.md §0.1/§0.2)** — `plans/451/ARCHITECTURE.md` lines 100, 143, 149 already reflect S9 completion (pre-applied in architect pass 9 before build). The ledger is current and accurate; the `gb` repo (separate from the `bpmner` PR) holds these files. | REJECT | ARCHITECTURE.md lives in the `gb` repo (never in a `bpmner` PR — per AGENTS.md §Planning artifacts). The ledger IS already correct. PLAN §7 exit gate 3 "same PR" means the same stage completes before merge — the pre-applied `gb`-side update satisfies this. The state is correct; no gap remains. | yes |
| 11 | Semantic | **`@NamedInterface` check** — None of the 18 changed files introduce `@NamedInterface`. | REJECT | No `@NamedInterface` in diff. §3.1 Rule 1 satisfied. | yes |
| 12 | Semantic | **Grant (`allowedDependencies`) unchanged** — No `*Module.kt` changes. Existing grants cover port-based injection. | REJECT | Correct per PLAN §5 non-goal 1. No new grants added. | yes |
| 13 | Semantic | **`BpmnFidelityReport` referenced without import in `BpmnContractFidelityPort.kt`** — Same package (`dev.groknull.bpmner.authoring`), no import needed in Kotlin. | REJECT | Pre-existing types at `authoring` root. Same-package reference — correct, no import needed. | yes |
| 14 | CI | **`test-unit`** — ✅ PASS (2m55s) on new head `209007e`. | REJECT | CI passed. | yes |
| 15 | CI | **`lint`** — ✅ PASS (13s) on new head. | REJECT | CI passed. | yes |
| 16 | CI | **`Greptile Review`** — ✅ PASS (4m26s) on new head. Greptile re-reviewed; 2 original inline comments (on old head) carry over (already replied + 👎); 1 new outside-diff comment on `BpmnContractAwareValidator.kt:25–26` (triaged as ACCEPT, row #21). Summary rates confidence 5/5, "Safe to merge." | REJECT | No net new inline Greptile comment on the re-seamed files beyond the stale-KDoc finding (row #21). | yes |
| 17 | CI | **`SonarCloud Scan`** — CI check still shows `pending` at audit time on new head. However, GitHub issue comment from `sonarqubecloud[bot]` confirms gate PASSED for both `bpmner Backend` and `bpmner Web` sub-projects (0 new issues, 87.6% coverage on new code, 0% duplication). SonarQube API confirms: quality gate OK, all 6 conditions pass. 0 open PR-scoped issues. | REJECT | SonarCloud PASS confirmed via issue comment and SonarQube API. CI check lag is a GitHub Checks timing artefact — not a failure. No issues to action. | yes |
| 18 | Greptile | **Comment #3463220846 (`BpmnContractFidelityPort.kt` lines 20–23)** — Claims "team rule that comments must describe the present state only"; past/future tense in Rationale paragraph. | REJECT | Not an established project rule. Architectural rationale KDoc with ADR references and historical context is standard in this codebase (cf. `RepairModuleTest`, `TelemetryModuleTest` KDoc). Replied with REJECT + rationale; 👎 applied. Greptile acknowledged and withdrew the comment. | yes |
| 19 | Greptile | **Comment #3463220945 (`BpmnContractFidelityChecker.kt` lines 83–85)** — "Past-tense relocation note should describe current structure." | REJECT | Same basis as #18. "Relocated from…as part of S9" is standard migration documentation; "Cross-module callers inject [BpmnContractFidelityPort] instead" is present-tense current structure. Replied with REJECT + rationale; 👎 applied. Greptile acknowledged and withdrew the comment. | yes |
| 20 | Semantic | **Merge conflict** — `mergeable: true`; `mergeable_state: unstable` (SonarCloud check pending timing lag). PR merges cleanly with `main` @ `cb02d5d`. | REJECT | No merge conflict. `unstable` is CI timing lag, not a conflict. | yes |
| 21 | Greptile | **Stale KDoc reference in `BpmnContractAwareValidator.kt:25–26`** — Class-level KDoc says `[BpmnContractFidelityChecker]` (concrete class) but the injected field is `fidelityChecker: BpmnContractFidelityPort`. This PR changed the field type (import + ctor) without updating the 2-line KDoc description. `BpmnContractFidelityChecker` is now in `authoring.internal.domain` — inaccessible from `repair.internal.domain`; the KDoc link is broken. On `main` the reference was valid (concrete class was directly injected). This PR made it stale. | ACCEPT | Net-new documentation accuracy defect introduced by this PR's re-seam of `BpmnContractAwareValidator`. Default to ACCEPT per audit rules. Fix: change line 25 KDoc from `[BpmnContractFidelityChecker]` to `[BpmnContractFidelityPort]`. Greptile outside-diff comment (id `outside-diff:12073746:0`) — not a standard GitHub review comment; no threaded reply possible. Finding recorded here as row #21. | no |

---

## Gate Results

| Gate | Status | Notes |
| --- | --- | --- |
| `lint` (CI) | ✅ PASS | 13s (new head) |
| `pr-title` (CI) | ✅ PASS | 4s |
| `Analyze (javascript-typescript)` (CI) | ✅ PASS | 39s |
| `test-unit` (CI) | ✅ PASS | 2m55s (new head) |
| `Greptile Review` (CI) | ✅ PASS | 4m26s; re-reviewed; 2 original comments REJECT; 1 new outside-diff finding (row #21 ACCEPT) |
| `SonarCloud Scan` (CI check) | ⏳ PENDING | CI check lag; gate confirmed PASS via issue comment + SonarQube API |
| SonarQube gate `kludgeworks_bpmner_backend` PR #466 | ✅ PASS | All 6 conditions OK; 87.6% new code coverage; 0 new issues |
| SonarQube gate `kludgeworks_bpmner_web` PR #466 | ✅ PASS | 0 new issues |
| SonarQube PR-scoped issues | ✅ NONE | 0 open issues returned for pullRequestId=466 |
| Merge conflict | ✅ CLEAN | `mergeable: true`; `mergeable_state: unstable` = CI lag only |

---

## Plan To Diff Coverage

| Deliverable | Status | Evidence |
| --- | --- | --- |
| L1: drop `internal` from `ProcessContractMarkdownRenderer` | ✅ COVERED | `contract/ProcessContractMarkdownRenderer.kt:14` |
| L2: relocate `BpmnContractFidelityChecker` to `authoring.internal.domain` | ✅ COVERED | Renamed; implements `BpmnContractFidelityPort`; `override fun check` |
| L3: relocate `DefaultFlowAssigner` to `authoring.internal.domain` | ✅ COVERED | Renamed; implements `BpmnDefaultFlowPort`; `override fun assign` |
| L4: seal `BpmnRequestResolver` behind port + relocate to `authoring.internal.domain` | ✅ COVERED | New file `authoring/internal/domain/BpmnRequestResolver.kt`; implements `BpmnRequestResolutionPort`; root class removed from `BpmnRequestDraft.kt` |
| New ports: `BpmnContractFidelityPort`, `BpmnDefaultFlowPort`, `BpmnRequestResolutionPort` | ✅ COVERED | All 3 at `authoring` root with `@PrimaryPort` |
| Re-seam: `LlmBpmnProcessGenerator` | ✅ COVERED | Injects ports |
| Re-seam: `BpmnContractAwareValidator` | ✅ COVERED | Injects `BpmnContractFidelityPort` |
| Re-seam: `BpmnRepairAdvancer` | ✅ COVERED | Injects `BpmnDefaultFlowPort` |
| Re-seam: `BpmnGenerationAgent` | ✅ COVERED | Injects `BpmnRequestResolutionPort` |
| `RepairModuleTest` KDoc + `TODO(#451-9)` removed | ✅ COVERED | KDoc settled Tier-3; TODO gone |
| `BpmnerModuleBoundariesTest` guard extension | ✅ COVERED | Comment block per §4.4 |
| `ARCHITECTURE.md` §0.1/§0.2 ledger | ✅ PRE-APPLIED | Already updated in `gb/plans/451/ARCHITECTURE.md` (architect pass 9; `gb` repo not `bpmner` PR) |

---

## Diff To Plan Coverage

All 18 changed files are justified by PLAN-451-9 §4 (§4.1–§4.4). No unplanned changes.

---

## Out Of Scope Files

None. No `*Module.kt`, no `docs/`, no `bpmn` kernel, no GraalJS bundle, no frozen ADRs changed.

---

## Open ACCEPT rows

| # | Finding | Action required |
| --- | --- | --- |
| 21 | Stale KDoc `[BpmnContractFidelityChecker]` at `BpmnContractAwareValidator.kt:25` — field type changed to `BpmnContractFidelityPort` but KDoc class description not updated | Change `[BpmnContractFidelityChecker]` → `[BpmnContractFidelityPort]` in the class-level KDoc (line 25 of `src/main/kotlin/dev/groknull/bpmner/repair/internal/domain/BpmnContractAwareValidator.kt`). One-line fix. |

---

*Audit pass 1 (2026-06-23). Audit pass 2 / closeout (2026-06-24). ACCEPT row #5 resolved. New ACCEPT row #21 (stale KDoc in BpmnContractAwareValidator). One open ACCEPT row — resolve before PR ready.*
