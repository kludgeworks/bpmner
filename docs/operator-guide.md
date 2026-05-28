# Operator Guide

This guide is for someone deploying bpmner or tuning it for a specific environment. It assumes you've read the [README](../README.md) and at least skimmed [`pipeline-architecture.md`](./pipeline-architecture.md). For internal mechanics see [`goap-lifecycle.md`](./goap-lifecycle.md).

## Configuration reference

Every `bpmner.*` YAML key, default, range, when to tune.

### Budgets

| Key | Default | Range | When to tune |
|---|---|---|---|
| `bpmner.budget.generation` | `100` | ≥ 1 | Bump if the generation+repair loop terminates with `ProcessExecutionTerminatedException` on inputs you believe are tractable. Lower at your peril — the budget covers generation AND the entire repair loop in one process. |
| `bpmner.budget.readiness` | `20` | ≥ 1 | Rarely tuned. Readiness has no repair loop; 20 is generous. |

Both fields live under a single `BpmnBudgetConfig` block in [`BpmnConfig.kt`](../src/main/kotlin/dev/groknull/bpmner/core/BpmnConfig.kt).

### Rule profile + severity overrides

| Key | Default | Effect |
|---|---|---|
| `bpmner.rules.profile` | `recommended` | Named profile loaded at startup. Phase 6 (#221) ships `recommended` (declared severities, nothing disabled) and `strict` (every WARNING-default rule bumped to ERROR). Unknown profile name fails startup with the list of available profiles. |
| `bpmner.rules.severity-overrides` | `{}` | Per-rule escape hatch applied **on top of** the active profile. User entries always win — the profile is the baseline, this map is per-deployment surgery. Keys are bare rule ids (e.g. `act-verb-object-name`); values are one of `error`, `warning`, `info`, `off`. |

Worked example — strict on a known-quiet rule, with a few escape-hatch carveouts:

```yaml
bpmner:
  rules:
    profile: strict
    severity-overrides:
      "act-activity-label-capitalization": "off"   # too noisy for our domain
      "name-business-meaningful-label": "warning"  # keep it visible but not blocking
```

**YAML 1.1 gotcha**: the literal `off` (and `on`, `yes`, `no`) is parsed as a boolean by SnakeYAML before Spring Boot's binder sees it. Spring then converts the Boolean to the string `"false"` / `"true"`, which the override parser doesn't recognise — the rule silently stays enabled. **Always quote severity values**: `"off"`, `"warning"`, `"error"`, `"info"`. The existing rule overrides in `application.yaml` follow this pattern.

### LLM role bindings

bpmner doesn't pick a model directly; it picks a *role*, and Embabel's role-mapping resolves the role to a concrete LLM. The roles bpmner uses:

| Role | Used by | Default model (gh profile) |
|---|---|---|
| `generator` | `BpmnGeneratorAgent.createOutline` | `gpt-4.1` |
| `repair-label` | `BpmnRepairAgent.applyLlmLabelPatch` | `gpt-4.1-nano` |
| `repair-patch` | `BpmnRepairAgent.applyLlmStructuralPatch` | `gpt-4.1-mini` |
| `repair-rewrite` | `BpmnRepairAgent.applyFullLlmRewrite` | `gpt-4.1` |
| `readiness-assessor` | `BpmnReadinessAgent.assessReadiness` | (profile default) |
| `contract-extractor` | `BpmnContractAgent.extractProcessContract` | (profile default) |
| `alignment-validator` | `BpmnAlignmentAgent.checkAlignment` | (profile default) |
| `linter` | `LlmRuleAgent.evaluateLlmRules` | (profile default) |

Override per-role under `embabel.models.llms.<role>`. Anthropic and GitHub Models profiles are configured in `application-anthropic.yaml` and `application-github.yaml` respectively. Run with `--spring.profiles.active=anth` or `gh`.

The `Persona` slot for each agent (`bpmner.generator`, `bpmner.repairer`, `bpmner.alignment-validator`, …) controls the system prompt voice. Defaults in [`BpmnConfig.kt`](../src/main/kotlin/dev/groknull/bpmner/core/BpmnConfig.kt) are tuned for the BPMN domain; override via YAML only if you're substantially changing the application's tone.

### Logging + diagnostics

| Key | Default | Effect |
|---|---|---|
| `bpmner.logging.dir` | `${BPMNER_LOG_DIR:${LOG_DIR:${BUILD_WORKING_DIRECTORY:${user.dir}}/logs}}` | Directory for run artifact dumps. |
| `bpmner.logging.dump-artifacts` | `false` | When `true`, every intermediate artifact (outline JSON, rendered XML, repair attempts) is logged at DEBUG with a length cap. Off for production. Override via env: `BPMNER_LOGGING_DUMP_ARTIFACTS=true`. |
| `bpmner.logging.artifact-preview-length` | `8000` | Truncation cap for dumped artifacts (characters). |

### Repair tuning

| Key | Default | Effect |
|---|---|---|
| `bpmner.repair.abbreviations` | small built-in map | Expansion map for the abbreviation auto-fix handler (e.g. `PNR → booking reference`). Add domain-specific entries; do NOT remove the bundled defaults unless you know the rule won't fire in your domain. |

## Reading diagnostics

A `BpmnDiagnostic` is what the validator emits and the repair loop dispatches on. Every field matters:

| Field | Type | What it tells you |
|---|---|---|
| `source` | `GRAPH` / `XSD` / `LINT` / `RENDER` | Which validator found it. `GRAPH` = `BpmnDefinitionValidator` (structural); `XSD` = schema; `LINT` = Pkl rule engine; `RENDER` = the renderer failed before validation. |
| `message` | string | Human-readable description. Often quotes the offending element id. |
| `severity` | `ERROR` / `WARNING` / `INFO` | Only `ERROR` is blocking. Repair fires on blocking diagnostics. |
| `rule` | nullable string | The bare rule id (e.g. `act-verb-object-name`). `null` for non-rule sources. |
| `elementId` / `objectRef` | nullable string | Which BPMN element triggered the diagnostic. |
| `repairScope` | `LABEL` / `OUTLINE` / `PHASE` / `COMPOSITION` / `FULL_PROCESS` | Which repair tier handles it. LABEL → label-patch (cost 0.5). OUTLINE/PHASE → structural-patch (0.7). FULL_PROCESS or none → full-rewrite (0.9). |
| `kind` | `LOCAL_MODEL_FIX` / `LLM_MODEL_PATCH` / `LLM_XML_REWRITE` / `UNFIXABLE` | The repair-action selector. `LOCAL_MODEL_FIX` → deterministic Kotlin handler. `UNFIXABLE` → no repair fires; the diagnostic surfaces to the user. |
| `repairSafety` | `SAFE_AUTOMATIC` / `SAFE_MANUAL` / `LLM_ONLY` | Reserved for operator policy. Not currently gating any repair tier. |
| `fixHandler` | nullable string | For `LOCAL_MODEL_FIX`, the registered handler name. |
| `ownerRef` | nullable string | Phase/pool ownership context. |

**Reading a diagnostic in a log**: source first (XSD = "the XML is wrong"; GRAPH = "the model is wrong"; LINT = "a rule disagrees"), then `kind` (predicts the repair tier), then `message`. If `kind = UNFIXABLE` you'll see this exact diagnostic in the final result — bpmner can't fix it; the operator must.

## Observability

bpmner emits structured log lines (SLF4J, bracketed placeholders) and Spring `@EventListener`-compatible events.

### Per-run summary

`BpmnerRunSummaryListener` logs one line per agent process completion:

```
Process bpmner-1234 completed in 38.5s actions=12 models=[gpt-4.1×3, gpt-4.1-mini×2] tokens=prompt=15234 completion=2891 cost=$0.18
```

### Per-validation summary

`BpmnRepairAgent.validate` and `revalidateAndAdvance` log one line per attempt:

```
Validation summary: graph=0, xsd=0, lint=2, repairScope=label=2, accepted=false, repairs=1
```

`graph` / `xsd` / `lint` are counts by source. `repairScope` groups by repair tier. `accepted = true` is the green-light state for `finalize`.

### Progress events (SSE)

`BpmnProgressProjectionObserver` translates each `@Action`'s start event into a friendly progress label and publishes `ProgressUpdateEvent`. Every `@Action` in the codebase is mapped — silent gaps would mean the UI stalls on "unknown step". To consume:

```kotlin
@Component
class MyProgressListener {
    @EventListener
    fun onProgress(event: ProgressUpdateEvent) {
        log.info("Bpmner step: {}", event.message)
    }
}
```

The mapping table lives in [`BpmnProgressProjectionObserver.kt`](../src/main/kotlin/dev/groknull/bpmner/observability/internal/adapter/inbound/BpmnProgressProjectionObserver.kt). Add a new entry whenever you add an `@Action` somewhere.

### Custom Embabel listeners

`ProcessOptions.listeners` is populated from Spring-collected `List<AgenticEventListener>`. Drop a `@Component` `AgenticEventListener` into the application context and it auto-wires into both `AgentPlatformBpmnAgentInvoker` (sync + async generation) and `AgentPlatformBpmnReadinessInvoker` (readiness). Useful for cost tracking, per-action timing, prompt logging.

## Troubleshooting

### `ProcessExecutionStuckException`

Surface: `AgentPlatformBpmnAgentInvoker.generate()` throws this. Caller sees it through `BpmnGenerationService.handleGenerationException`.

Meaning: the planner can't find any applicable action. The most common cause is that every remaining diagnostic has `kind = UNFIXABLE` — no repair tier matches.

Diagnose:
1. Look at the per-validation summary log line. If `repairScope` is empty and `accepted=false`, you're stuck.
2. Grep the run for `UNFIXABLE` diagnostics. Their `message` field tells you what bpmner gave up on.
3. Decide: is the diagnostic actually unfixable (a logic error the user must fix), or is the rule's `kind` mis-classified? If the latter, change the Pkl rule's `Repair.kind` and add a handler.

### `ProcessExecutionTerminatedException`

Surface: same as above.

Meaning: `Budget(actions = N)` exhausted before reaching the goal. **Important**: `ReplanRequestedException` does **not** count against this budget — only successful action completions do. So a TERMINATED process has spent N actions actually doing work.

Common cause: an LLM action keeps returning *almost-correct* outputs, none of which fully resolve `diagnosticsResolved`. Each attempt is one budget action.

Diagnose:
1. Grep for `Validation summary` lines. Count them — that's roughly the budget consumed.
2. Look at the diagnostic counts trend. If they're decreasing slowly, the LLM is making progress and you might just need a higher budget. If they're stable or oscillating, escalation isn't working.
3. Check the repairScope distribution. If it stays at `full_process` and `applyFullLlmRewrite` keeps running without resolving, the LLM may need a stricter prompt — or the diagnostics may need a different `repairScope` so a cheaper tier can pick them up.

### `BpmnAlignmentException`

Surface: thrown by `BpmnAlignmentAgent.checkAlignment`. Caught in `BpmnGenerationService.handleGenerationException` and returned to the caller as `BpmnResult(status = ALIGNMENT_FAILED, alignmentReport = ?)`.

Two flavours, distinguished by `report` nullability:
- **`report != null`** — alignment model examined the BPMN and found problems. Read `report.issues` for the breakdown.
- **`report == null`** — alignment model itself failed (LLM call threw `InvalidLlmReturnFormatException` or `InvalidLlmReturnTypeException`). The exception message has the framework detail; the BPMN was never actually checked. Retry usually fixes this.

### Stuck repair fingerprint

Phrase to grep for in logs: `Repair attempt N` (the per-attempt summary). If you see the same diagnostic counts attempt after attempt, the fingerprint guards should have already fired a `ReplanRequestedException` — but if they're not, that's a bug worth reporting. The expected behaviour is: same-fingerprint → replan signal → planner blacklists the action → planner picks a different action.

### Unknown profile name

Surface: startup fails fast with:

```
Unknown rule profile 'foo'. Available profiles: recommended, strict.
Drop a {Name}Profile.pkl file into linter/pkl/profiles/ to add a new one.
```

The error message lists every available profile. Either fix the YAML or add a new `.pkl` profile (see [`linter/docs/rule-authoring-guide.md`](../linter/docs/rule-authoring-guide.md)).

### YAML config-binding errors at startup

Spring Boot's `@ConfigurationProperties` validation is now wired (`@Validated` on `BpmnConfig`). A malformed value (e.g. negative budget) will fail startup with a clear `ConstraintViolationException`. Check the offending field — `@field:Min`/`@field:Max` are declared next to each constrained property.

## When to upgrade

- **Phase 4 typed exceptions surface via `AgentProcessExecution.fromProcessStatus()`** — landed in PR #274. If you're seeing generic `IllegalArgumentException("Cannot get result … Status=STUCK")` instead of `ProcessExecutionStuckException`, you're on a pre-Phase-4 build or someone migrated the invoker to `AgentPlatformTypedOps.transform()` (which uses the older path). The current invoker (`AgentPlatformBpmnAgentInvoker.generate`) is intentionally NOT using TypedOps for this reason.

- **Per-call-site `ProcessOptions`** — landed in PR #276 (Phase 5). Older builds hardcoded `Budget(actions = 100)` in two places; current builds read from `bpmner.budget.*`.

- **Named profiles** — Phase 6 (#221). Older builds only honoured `bpmner.rules.severity-overrides`; setting `bpmner.rules.profile` on a pre-Phase-6 binary is silently ignored.
