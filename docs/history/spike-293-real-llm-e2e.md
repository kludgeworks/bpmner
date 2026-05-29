# Spike #293: e2e tests with a real LLM via GitHub Models

## Context

Before this spike, bpmner's test pyramid was:

1. **Unit tests** — `FakeOperationContext` / Mockito mocks, no LLM calls.
2. **Integration tests** — `EmbabelMockitoIntegrationTest` (real Spring Boot + GOAP planner, mocked `LlmOperations`).
3. **End-to-end tests** — none.

No test had ever validated that the prose → readiness → contract → BPMN → render → lint round-trip *actually works against a real LLM*. Prompt changes, vocabulary additions from epic #196, and rule activations could silently break the pipeline with no signal until manual testing.

#293 was a spike: prove out that a real-LLM smoke test could run on every PR using GitHub Models (free LLM access via the workflow's `GITHUB_TOKEN`).

## What was built

A working test scaffold:

- **`BpmnEmployeeOnboardingE2eTest`** — `@SpringBootTest` + `@ActiveProfiles("github")` + `@EnabledIfEnvironmentVariable("GITHUB_TOKEN")`. Loads `samples/employee-onboarding.prose.md` via Bazel runfiles, invokes the full pipeline through `AgentPlatformTypedOps.transform()`, and asserts structurally (`status == GENERATED`, XML well-formed, output file matches result).
- **Bazel tagging** — `*E2eTest.kt` files automatically get the `["e2e"]` tag and `"long"` timeout. `.bazelrc` default filter switched from `-integration` to `-e2e`, which also promoted 2 previously-filtered `*IntegrationTest.kt` files to default CI runs (they're mocked LlmOperations, deterministic and free).
- **Sample loading** — new `samples/BUILD.bazel` filegroup + `data` attr on `bpmner_tests_lib`.
- **CI step** — `Run e2e smoke tests (real LLM)` with `permissions: { models: read, contents: read }`, `GITHUB_TOKEN` env, and `continue-on-error: true` as workflow-level rate-limit safety.
- **Tier-limit handling** — the test catches GitHub Models tier policy responses (`429` rate limit and `413` tokens-exceeded) and reports them as JUnit *skipped* via `Assumptions.assumeTrue(false, …)`, so policy hiccups never mark a PR red.

This infrastructure works. The Bazel queries, the tagging, the CI step, the env-var guard, and the prose loading have all been verified end-to-end.

## Findings

### Bug 1 — `github` profile registered no usable model

`application-github.yaml`'s role map references the `openai/gpt-4.1` family. `GitHubProperties.models` defaulted to `["openai/gpt-4o"]` only. Spring context startup blew up with:

```
Default LLM 'openai/gpt-4.1' not found in available models: [openai/gpt-4o]
```

Latent since commit `9fb60aa` flipped the role map from `gpt-4o` to `gpt-4.1`; no test had ever exercised the `github` profile end-to-end. **Fixed in commit `9a91189`** by adding an explicit `models:` list to `application-github.yaml`.

### Bug 2 — pipeline payload exceeds GitHub Models' free-tier input cap

GitHub Models' free tier caps **per-request input** at 8,000 tokens for the OpenAI `gpt-4.1` family (the best non-Enterprise tier). Verified against [GitHub's docs](https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models):

| Tier | Per-request input | Per-request output |
|---|---|---|
| Low (gpt-4.1-mini/nano) | 8,000 | 4,000 |
| High (gpt-4.1) | 8,000 | 4,000 |
| Custom (gpt-5, o1, o3, Grok, etc.) | 4,000 | 4,000 |
| Copilot Enterprise (high) | 16,000 | 8,000 |
| Embeddings | 64,000 | — |

The catalog's `ctx-in: 1M` for `gpt-4.1` is the model's underlying capability, not what the free tier passes through. **Switching to another free-tier text model does not raise the cap** — most are 4K or 8K. The Copilot Enterprise 16K tier requires a license; it's not available via free `GITHUB_TOKEN`.

An offline probe (`ContractPromptSizeProbeTest`) measured the actual payload for `BpmnContractAgent.extractProcessContract`:

```
Prompt:        10,208 chars  (~2,550 tokens)
Schema:        25,537 chars  (~8,500 tokens)
Total:         35,745 chars  (~11,000 tokens)
GH Models cap:                8,000 tokens
Headroom:                    -3,000 tokens
```

**The JSON schema alone (~8,500 tokens) already exceeds the 8K cap.** Trimming the prompt to zero would not make the request fit.

### Root cause of schema bloat

The dominant contributors to the schema are sealed-type subtype repetition with `@JsonPropertyDescription` annotations:

| Type | Subtypes | Approx description weight |
|---|---|---|
| `ContractActivity` | 7 (Service, User, Script, BusinessRule, Send, Receive, Manual) | ~1.4 KB per subtype × 7, with 200+ char descriptions on discriminated fields like `decisionName`, `messageName` |
| `ContractEndState` | 6 (Normal, Terminate, Error, Message, Signal, Escalation) | similar — `errorCode`, `messageName`, `signalName`, `escalationCode` all carry 200+ char descriptions |
| `ContractBranch` | 3 (Conditional, Default, Unconditional) | 200+ char `condition` description on multiple subtypes |
| Core `ProcessContract` | 9 properties | ~0.8 KB of descriptions |
| `@JsonClassDescription` per subtype | every subtype | adds 60–120 chars × 16+ subtypes |

There's also substantial duplication between the schema descriptions and the static prompt text in `BpmnContractPromptFactory` — both explain what each activity kind / end-state kind means.

## Plan: what real-LLM e2e testing could look like

Three viable paths, in order of cost-to-benefit.

### Option A — Schema and prompt de-bloat (cheapest, biggest blast radius on `src/main/`)

Aggressively reduce the contract-extraction payload:

1. **Drop `@JsonClassDescription` on every `ContractActivity` and `ContractEndState` subtype** — keep only the parent class's discriminator description. Estimated saving: 1–2 KB.
2. **Drop `@JsonPropertyDescription` on the four core fields (`id`, `name`, `actorId`, `sourceIds`) of every sealed subtype** — they're redundant with the parent. Estimated saving: 3–4 KB.
3. **Shorten `@JsonPropertyDescription` on discriminated fields** to ≤80 chars (e.g. `messageName`, `errorCode`, `condition`). Saving: 1–2 KB.
4. **Remove duplication between schema descriptions and `BpmnContractPromptFactory` prompt text** — pick one source of truth per concept. Saving: 2–3 KB on the prompt.

Best-case total reduction: ~8–10 KB → schema drops from 25.5 KB to ~16 KB (~5,300 tokens), prompt drops to ~7 KB (~1,800 tokens). Total ≈ 7,100 tokens — *just under* 8K with the prose sample.

**Risks**: LLM output quality may degrade — those descriptions are the prompt-side guidance for how to classify ambiguous prose. Worth measuring against prose samples before merging. Touches `src/main/` extensively. Separate from the e2e infra and best done as its own issue (#293 follow-up).

**Verdict**: feasible but risky for production quality. The 8K headroom is razor-thin — any future vocabulary addition (#196 sub-issues add subtypes) will push us back over.

### Option B — Pivot e2e to a paid LLM endpoint

Add a second Spring profile (e.g. `e2e-openai`) that uses a paid API key from a GitHub Actions secret (`OPENAI_API_KEY` or `ANTHROPIC_API_KEY`). The `github` profile stays as-is for whatever non-e2e flows want a free LLM (today: none — no production code uses it).

Costs:
- One smoke test × `employee-onboarding.prose.md` × ~4–6 LLM calls ≈ <$0.05/run at GPT-4.1 pricing. <$1/month at 20 PRs/day.
- Setup: one GitHub Actions secret + one YAML file + one CI step env var.

The e2e test stays the same shape; only the active profile and base URL change. No `src/main/` rewrites.

**Verdict**: structurally honest. GitHub Models' free tier is not designed for this payload size, and the spike has now proved that empirically. A paid endpoint is the right tool for the job. The infrastructure built in this PR is profile-agnostic — point it at any OpenAI-compatible endpoint.

### Option C — Split contract extraction into smaller LLM calls

Refactor `BpmnContractAgent.extractProcessContract` to make multiple smaller LLM calls — e.g. extract activities, decisions, and end-states in separate prompts, then merge. Each sub-prompt would carry only the relevant schema slice.

Costs:
- Significant `src/main/` refactor.
- More LLM calls per run → more 429 risk on free tier.
- Loss of cross-cutting context (the LLM no longer sees the whole picture when extracting decisions).

**Verdict**: invasive, doesn't fit the spike's scope, and the loss of holistic context may hurt extraction quality. Worth considering only if Option A's quality risk is unacceptable AND Option B's cost is unacceptable.

### Option D — Different free LLM provider entirely

Providers like Groq, Cerebras, or Together AI offer free tiers with different (and sometimes higher) per-request limits. The e2e infrastructure is profile-agnostic; we could point it at any OpenAI-compatible endpoint.

**Verdict**: a reasonable alternative to Option B if cost is a hard blocker. Adds operational complexity (new provider account, new token, new failure modes). Worth checking once if anyone has a strong free-tier preference, but Option B is simpler.

## Recommendation

**Option B** (paid LLM endpoint for e2e) is the cleanest answer. The spike has empirically established that GitHub Models' free tier can't accommodate bpmner's current pipeline, and the marginal cost of a paid smoke test is trivial relative to the engineering cost of Option A's quality risk or Option C's refactor.

The infra built in this PR is the same regardless of which provider e2e uses — the `e2e` Bazel tag, the CI step, the env-var guard, the tier-limit skip path, and the prose-loading wiring all carry over.

If Option B is taken, this PR can merge as-is. The e2e test will SKIP cleanly on GitHub Models due to 413 until the profile pivots to a paid endpoint, but the foundation is in place.

## Status

PR #295 (this branch) ships:

- Working e2e test infrastructure (Bazel tag, CI step, runfiles loading, env-var guard, tier-limit skip).
- Fix for Bug 1 (`application-github.yaml` model registration).
- Tier-limit-aware test skip path (catches 413 and 429).
- Offline schema/prompt sizing probe (`ContractPromptSizeProbeTest`) for future re-measurement if Option A is attempted.
- This document.

What it does NOT ship:

- A green real-LLM run. The test will SKIP on GitHub Models due to Bug 2 (8K cap < 11K payload). Resolving this requires a separate decision on Option A, B, C, or D.
