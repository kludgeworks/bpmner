# Operator Guide

This guide is for someone deploying bpmner or tuning it for a specific environment. It assumes you've read the [README](../README.md) and at least skimmed the [Architecture doc](./architecture.md). For internal pipeline and GOAP mechanics see the [architecture doc](./architecture.md).

## Configuration reference

Every `bpmner.*` YAML key, default, range, when to tune.

### Budgets

| Key | Default | Range | When to tune |
| --- | --- | --- | --- |
| `bpmner.budget.generation` | `100` | ≥ 1 | Bump if the generation+repair loop terminates with `ProcessExecutionTerminatedException` on inputs you believe are tractable. Lower at your peril — the budget covers generation AND the entire repair loop in one process. |
| `bpmner.budget.readiness` | `20` | ≥ 1 | Rarely tuned. Readiness has no repair loop; 20 is generous. |

Both fields are bound at `bpmner.budget` via `@ConfigurationProperties` in the authoring and readiness capability config files.

### Rule profile + severity overrides

| Key | Default | Effect |
| --- | --- | --- |
| `bpmner.rules.profile` | `recommended` | Named profile loaded at startup. Built-in profiles: `recommended` (declared severities, nothing disabled) and `strict` (every WARNING-default rule bumped to ERROR). Unknown profile name fails startup with the list of available profiles. |
| `bpmner.rules.severity-overrides` | `{}` | Per-rule escape hatch applied **on top of** the active profile. User entries always win — the profile is the baseline, this map is per-deployment surgery. Keys are bare rule ids (e.g. `act-verb-object-name`); values are one of `error`, `warning`, `info`, `off`. |
| `bpmner.rules.config-uri` | `modulepath:/linter/pkl/bpmner.pkl` | Modeller-owned convention source for word lists used by selected naming rules and the `stripTypeWords` local repair handler. Leave unset for packaged defaults; set to a `file:` URI for a team-specific `bpmner.pkl`. |

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

### Rule conventions (`bpmner.pkl`)

The packaged `modulepath:/linter/pkl/bpmner.pkl` amends `BpmnerLintConfig.pkl` and supplies the default convention lists used by Kotlin-authored rule beans. To customise those lists, create a local Pkl file that amends the packaged template and point `bpmner.rules.config-uri` at it with a `file:` URI:

```pkl
amends "modulepath:/linter/pkl/BpmnerLintConfig.pkl"

elementTypeWords = List("activity", "process", "event", "step")
allowedAcronyms = List("BPMN", "SLA", "API", "CRM")
technicalTokens = List("api", "svc", "tbl", "req", "resp", "tmp", "proc", "obj")
discouragedLeadingVerbs = List("handle", "manage", "process", "perform", "do")
discouragedBpmnTypes = List("bpmn:Transaction")
```

The convention fields are:

| Field | Used by |
| --- | --- |
| `discouragedLeadingVerbs` | `act-discouraged-business-verbs` |
| `elementTypeWords` | `name-no-element-type-words`, `data-no-type-words-in-data-name`, and `stripTypeWords` repair |
| `allowedAcronyms` | `name-uncommon-abbreviations` allowed vocabulary |
| `technicalTokens` | `name-business-meaningful-label` forbidden vocabulary |
| `discouragedBpmnTypes` | `gen-bpmn-subset` target elements |

Profile and severity decisions are not read from `bpmner.pkl` at runtime; they come from `bpmner.rules.profile` and `bpmner.rules.severity-overrides`.

### LLM role bindings

bpmner doesn't pick a model directly; it picks a *role*, and Embabel's role-mapping resolves the role to a concrete LLM. The roles bpmner uses:

| Role | Used by | Default model |
| --- | --- | --- |
| `generator` | `BpmnGenerationAgent.createOutline` | `gpt-4.1` |
| `repair-label` | `BpmnLlmRepairApplier.applyLlmLabelPatch` | `gpt-4.1-nano` |
| `repair-patch` | `BpmnLlmRepairApplier.applyLlmStructuralPatch` | `gpt-4.1-mini` |
| `repair-rewrite` | `BpmnLlmRepairApplier.applyFullLlmRewrite` | `gpt-4.1` |
| `readiness-assessor` | `BpmnReadinessAgent.assessReadiness` | (profile default) |
| `contract-extractor` | `LlmProcessContractExtractor` | (profile default) |
| `alignment-validator` | `LlmBpmnAligner` | (profile default) |

Override per-role under `embabel.models.llms.<role>`. Provider profiles are configured in `application-anthropic.yaml`, `application-openai.yaml`, `application-gemini.yaml`, `application-mistral.yaml`, `application-deepseek.yaml`, and `application-llama.yaml`. The simplest way to run is `mise run bpmner-cli --provider <provider>` — one of `anthropic`, `openai`, `gemini`, `mistral`, `deepseek`, or `llama` (Llama on Cerebras via the OpenRouter proxy); the task reads that provider's API key from 1Password (`op://bpmner/<provider>/api-key`) and sets `SPRING_PROFILES_ACTIVE` for you (add `--web` or `--verbose` to layer on those profiles). To run `bazel` directly instead, pass `--spring.profiles.active=<provider>` (or set `SPRING_PROFILES_ACTIVE`) and ensure the corresponding API key environment variable is set (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`, `MISTRAL_API_KEY`, `DEEPSEEK_API_KEY`, or `OPENROUTER_API_KEY`).

The `Persona` slot for each agent (`bpmner.generator`, `bpmner.repairer`, `bpmner.alignment-validator`, …) controls the system prompt voice. Defaults are tuned for the BPMN domain in the capability-owned config beans; override via YAML only if you're substantially changing the application's tone.

### Logging + diagnostics

| Key | Default | Effect |
| --- | --- | --- |
| `bpmner.logging.dir` | `${BPMNER_LOG_DIR:${LOG_DIR:${BUILD_WORKING_DIRECTORY:${user.dir}}/logs}}` | Directory for run artifact dumps. |
| `bpmner.logging.dump-artifacts` | `false` | When `true`, every intermediate artifact (outline JSON, rendered XML, repair attempts) is logged at DEBUG with a length cap. Off for production. Override via env: `BPMNER_LOGGING_DUMP_ARTIFACTS=true`. |
| `bpmner.logging.artifact-preview-length` | `8000` | Truncation cap for dumped artifacts (characters). |

## Reading diagnostics

A `BpmnDiagnostic` is what the validator emits and the repair loop dispatches on. Every field matters:

| Field | Type | What it tells you |
| --- | --- | --- |
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

```text
Process bpmner-1234 completed in 38.5s actions=12 models=[gpt-4.1×3, gpt-4.1-mini×2] tokens=prompt=15234 completion=2891 cost=$0.18
```

### Per-validation summary

`BpmnEvaluationPipeline` logs one line per validation attempt:

```text
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

The mapping table lives in [`BpmnProgressProjectionObserver.kt`](../src/main/kotlin/dev/groknull/bpmner/telemetry/internal/adapter/inbound/BpmnProgressProjectionObserver.kt). Add a new entry whenever you add an `@Action` somewhere.

### Custom Embabel listeners

`ProcessOptions.listeners` is populated from Spring-collected `List<AgenticEventListener>`. Drop a `@Component` `AgenticEventListener` into the application context and it auto-wires into both `AgentPlatformBpmnAgentInvoker` (generation) and `AgentPlatformBpmnReadinessInvoker` (readiness). Useful for cost tracking, per-action timing, prompt logging.

## BPMN preview

After each successful interactive `generate` run the shell prompts the user to open the generated
diagram in a browser. This section describes the gate, artifact naming, output lines, and how the
feature behaves in non-interactive and headless environments.

### Gate order

The orchestrator (`BpmnPreviewOrchestrator`) evaluates three conditions in order before opening a
browser:

1. **`canOpenBrowser()` is true** — the interactivity gate (`InteractiveEnvironment`) checks (a)
   `GraphicsEnvironment.isHeadless()` is false, (b) the `CI` environment variable is absent
   (`System.getenv("CI") == null`), and (c) `System.console() != null`. All three must hold.
2. **`confirmOpenPreview()` is true** — the shell prompts `Open preview in browser? [Y/n]:`.
   Blank or empty input defaults to **Yes**; `y`/`yes` (case-insensitive) is Yes; any other input
   is No.
3. **The BPMN file exists on disk** — the resolved output path must be present.

If any condition fails the result is `Skipped` and the output is byte-for-byte unchanged.

### Artifact naming

The HTML preview is written beside the `.bpmn` file using the sibling naming convention
`<stem>.preview.html`. For example, `output.bpmn` → `output.preview.html`. The preview file is
always in the same directory as the BPMN output.

### Output lines

<!-- markdownlint-disable MD013 -->

| `PreviewResult` | Line(s) appended after `Wrote BPMN to: <path>` |
| --- | --- |
| `Skipped` | *(none — output is unchanged)* |
| `Opened` | `Preview opened in browser: <path>` |
| `Fallback` (unsupported or failed browser) | `Preview written to: <path>` followed by the reason on the next line |
| `WriteFailed` (preview file write error) | The error reason followed by `Source BPMN: <path>` |

<!-- markdownlint-enable MD013 -->

On `Fallback` and `WriteFailed` the preview path (or BPMN path) is always printed so the operator
can open the file manually.

### Non-interactive and CI environments

The interactivity gate short-circuits to `Skipped` — no prompt is shown and no browser is opened —
whenever **any** of the following is true:

- `CI` environment variable is set (presence check, any value).
- `System.console()` returns null (piped stdin/stdout, non-TTY, most application servers).
- `GraphicsEnvironment.isHeadless()` returns true (headless JVM, X11-less Linux servers).

This means automation never hangs waiting for input. The combined gate covers CI systems (GitHub
Actions, Jenkins, etc.), piped shell commands, and server JVMs.

## Troubleshooting

### Browser preview not opening

The preview prompt and browser launch can fail silently or produce a `Fallback` output line in
several common environments. The preview file path is always printed on `Fallback` or `WriteFailed`
so you can open it manually.

#### Remote shells (SSH, tmux, screen)

`System.console()` returns null for most non-login SSH sessions and multiplexer panes because stdin
is not a real TTY. The interactivity gate sees "no console" and returns `Skipped` — no prompt is
shown. To open the preview, copy the printed `.preview.html` path to your local machine and open it
in a browser, or use port-forwarding/scp to retrieve the file.

#### Minimal Linux without xdg-open

On container images or stripped server installs `xdg-open` may not be present. The `DesktopBrowserOpener`
falls back to `xdg-open` on Linux after trying `Desktop.Action.BROWSE`; if neither succeeds the result
is `Fallback` and the preview path is printed. Install `xdg-utils` (`apt install xdg-utils`) and set
`DISPLAY` or `WAYLAND_DISPLAY` if you want automatic browser opening on a Linux desktop.

#### Headless servers (no display)

`GraphicsEnvironment.isHeadless()` returns true when no graphics environment is available (no `DISPLAY`
variable, no AWT peer). The interactivity gate returns `Skipped` immediately so AWT is never
initialised and no exception is thrown. The `.preview.html` file is only written on explicit opt-in
(user answers Yes in a non-headless shell), so on headless servers the preview file will not be present.
Run `generate` from a local developer workstation to produce and open the preview.

#### Desktop.Action.BROWSE unsupported

Some Linux desktop environments do not expose `Desktop.Action.BROWSE` via the JVM's `java.awt.Desktop`
API. In this case the opener falls back to `xdg-open <path>`. If that also fails, the result is
`Fallback` and the preview path is printed for manual opening.

### `ProcessExecutionStuckException`

Surface: `AgentPlatformBpmnAgentInvoker.generate()` throws this for synchronous programmatic use; async shell/web processes expose the process state through Embabel.

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

Surface: thrown by `BpmnAlignmentAgent.checkAlignment`. Embabel records the failed process state; synchronous programmatic callers see the typed exception from `AgentPlatformBpmnAgentInvoker.generate()`.

Two flavours, distinguished by `report` nullability:

- **`report != null`** — alignment model examined the BPMN and found problems. Read `report.issues` for the breakdown.
- **`report == null`** — alignment model itself failed (LLM call threw `InvalidLlmReturnFormatException` or `InvalidLlmReturnTypeException`). The exception message has the framework detail; the BPMN was never actually checked. Retry usually fixes this.

### Stuck repair fingerprint

Phrase to grep for in logs: `Repair attempt N` (the per-attempt summary). If you see the same diagnostic counts attempt after attempt, the fingerprint guards should have already fired a `ReplanRequestedException` — but if they're not, that's a bug worth reporting. The expected behaviour is: same-fingerprint → replan signal → planner blacklists the action → planner picks a different action.

### Unknown profile name

Surface: startup fails fast with:

```text
Unknown rule profile 'foo'. Available profiles: recommended, strict.
Drop a {Name}Profile.pkl file into linter/pkl/profiles/ to add a new one.
```

The error message lists every available profile. Either fix the YAML or add a new `.pkl` profile (see [`linter/docs/rule-authoring-guide.md`](../linter/docs/rule-authoring-guide.md)).

### YAML config-binding errors at startup

Spring Boot's `@ConfigurationProperties` validation is wired via `@Validated` on `BpmnConfig`. A malformed value (e.g. a negative budget) fails startup with a clear `ConstraintViolationException`. Check the offending field — `@field:Min` / `@field:Max` are declared next to each constrained property.
