# bpmner

[![CI](https://github.com/kludgeworks/bpmner/actions/workflows/ci.yml/badge.svg)](https://github.com/kludgeworks/bpmner/actions/workflows/ci.yml)
[![Quality Gate — Backend](https://sonarcloud.io/api/project_badges/measure?project=kludgeworks_bpmner_backend&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=kludgeworks_bpmner_backend)
[![Quality Gate — Web](https://sonarcloud.io/api/project_badges/measure?project=kludgeworks_bpmner_web&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=kludgeworks_bpmner_web)
[![Reviewed by Greptile](https://img.shields.io/badge/reviewed%20by-greptile-1f8acb)](https://www.greptile.com)

Generates valid, semantically-grounded BPMN 2.0 XML from plain-language workflow descriptions — business, automated, technical, scientific, or personal.

`bpmner` is more than a simple BPMN generator. It is a high-integrity modeling assistant that bridges the gap between ambiguous human language and technical process standards. Built on the [Embabel](https://github.com/embabel/embabel-agent) agentic framework, it employs a defense-in-depth pipeline to ensure every generated diagram is not only syntactically correct but also semantically aligned with user intent.

## Core Pillars

### 1. Semantic Integrity (The Guardrail Pipeline)

Traditional LLM generation often suffers from "hallucinations" or missing requirements. `bpmner` mitigates this with a unique three-stage guardrail system:
- **Readiness Assessment:** Analyzes the input for modeling suitability. If the description is too vague, the system blocks generation and initiates an **Interactive Clarification Flow** to gather missing facts.
- **Process Contract Extraction:** Derives a structured "contract" from the source text and clarifications. This contract serves as the source of truth, grounding every subsequent generation step in evidence.
- **Semantic Alignment:** After generation, the system performs a terminal check comparing the BPMN elements against the Process Contract. Any invented tasks or missing branches are detected and reported, preventing ungrounded models from being delivered.

### 2. Technical Quality (XSD & Pkl rule engine)

Every diagram is strictly validated against:
- The official **BPMN 2.0 XSD**.
- Kotlin graph-integrity checks that reject malformed topology such as dangling flows or orphaned non-terminal nodes before output is accepted.
- A custom **Pkl-authored rule catalog** with specialized rules enforcing industry best practices (naming conventions and structural logic). Rules are evaluated in-process by a Kotlin `RuleEngine`; GraalJS is used only for the diagram auto-layout pass.

### 3. Deterministic Repair

When validation fails, `bpmner` doesn't just "try again." It uses a **local-first repair loop** that attempts to fix technical issues (like ID mismatches or simple naming violations) using deterministic Kotlin handlers before falling back to the LLM for more complex semantic refactoring.

## Model Routing

Each LLM call routes through a named **role** (e.g. `repair-label`) rather than a hard-coded model. Roles let us match model cost and capability to the task: a label tweak goes to a small fast model, a full rewrite goes to a large one. Deterministic validation (XSD, Pkl rule engine) and `DeterministicTopologyRepairStrategy` never call an LLM — they are pure Kotlin and remain that way by design.

| Role             | Persona                      | Requirement                      |
| ---------------- | ---------------------------- | -------------------------------- |
| `generator`      | BPMN Designer                | Large context, complex reasoning |
| `repair-label`   | BPMN Label Copy Editor       | Small, fast, detail-oriented     |
| `repair-patch`   | BPMN Patch Repair Specialist | Intermediate, structural logic   |
| `repair-rewrite` | BPMN Full Rewrite Specialist | Large context, holistic design   |

Roles are also defined for `readiness-assessor` (balanced), `contract-extractor` (high precision), and `alignment-validator` (critical); see `src/main/resources/application*.yaml` for the full list.

**Where to change it.** Persona definitions and role names live in the capability-owned config files under `src/main/kotlin/`. Concrete model assignments live in `src/main/resources/application*.yaml`. Adding a provider is a YAML-only change; adding a new role requires updating the relevant config bean and an entry in every active profile.

**When to promote a task to a larger model.** If a small-tier role starts producing bad fixes — for example `repair-label` mangling capitalization — bump that role to the next tier in the relevant `application-*.yaml`. No code change is needed; the routing key is the role name.

## Getting Started

### Prerequisites
- **Bazel 8.6.0** (pinned in `.bazelversion`) — install via [Bazelisk](https://github.com/bazelbuild/bazelisk).
- **Mise** for environment and tool management.
- An LLM API key for at least one supported provider, stored in 1Password and read via the `op` CLI (see [Run](#run)).

### Build

```bash
bazelisk build //src:bpmner_app
```

### Run

`bpmner` runs in two modes: a browser-based web UI and the Embabel interactive shell. Both launch
through the `bpmner-cli` mise task, which loads the chosen provider's API key from 1Password
(`op://bpmner/<provider>/api-key`), sets the matching Spring profile via `SPRING_PROFILES_ACTIVE`, and
runs `bazelisk run //src:bpmner_app`. Sign in to 1Password first (`op signin`, or set
`OP_SERVICE_ACCOUNT_TOKEN`).

Select a provider with `--provider` — one of `anthropic`, `openai`, `gemini`, `mistral`, `deepseek`,
or `llama`; omit it to choose interactively (via `gum`). Add `--web` for the browser UI and `--verbose`
for DEBUG logging.

#### Web Interface

Starts an HTTP server with a live, progress-aware browser UI for submitting process descriptions, watching generation progress over SSE, inspecting intermediate validation snapshots, and downloading the final BPMN XML. The web flow keeps XML in memory rather than writing `.bpmn` files to disk.

```bash
mise run bpmner-cli --provider anthropic --web
```

Open `http://localhost:8080` once the server is up.

#### Interactive Shell

Start the shell and use the dedicated `generate` command (also aliased as `gen` or `g`) to create a
BPMN diagram from a plain-language description. The shell owns prompting, blackboard inspection, cost
output, and tool statistics.

```bash
mise run bpmner-cli --provider anthropic
```

Then run, for example:

```text
generate "Order fulfilment workflow with payment authorisation and shipping notification"
generate --output order-fulfilment.bpmn "Order fulfilment workflow"
```

Omitting `--output` lets the LLM generate a descriptive kebab-case name from the process description
(e.g. `purchase-order-approval.bpmn`). Embabel's built-in `x` / `execute` command also works, but
`generate` adds the dedicated post-generation preview prompt described below.

##### Post-generation browser preview

After each successful `generate` run the shell asks whether to open the diagram in a browser:

```text
Open preview in browser? [Y/n]:
```

Press **Enter** (or type `y`/`yes`) to accept — the default is **Yes**. The command writes a
`<stem>.preview.html` file beside the `.bpmn` output (e.g. `output.preview.html` next to
`output.bpmn`) and asks the OS to open it.

<!-- markdownlint-disable MD013 -->

**Output lines you will see:**

| Outcome | Line(s) appended |
| --- | --- |
| Browser opened successfully | `Preview opened in browser: <path>` |
| Browser unsupported / launch failed | `Preview written to: <path>` followed by the reason |
| Preview file could not be written | `<reason>` followed by `Source BPMN: <path>` |
| Non-interactive / CI / headless / user declined / path not found | *(no line — output is unchanged)* |

<!-- markdownlint-enable MD013 -->

**Non-interactive and CI runs skip the prompt entirely** — a run is considered non-interactive when
any of the following hold: the `CI` environment variable is set, no system console is attached
(`System.console()` returns null), or the JVM is in headless mode (`GraphicsEnvironment.isHeadless()`
returns true). In those cases the prompt is never shown and the output is byte-for-byte identical to a
run without the preview feature. Automation never hangs.

See the [Operator Guide](docs/operator-guide.md#bpmn-preview) for headless/CI configuration and
browser-open troubleshooting.

## Observability & Tracing

`bpmner` is built for production observability:
- **Structured Run Summaries:** Every run produces a JSONL summary containing execution timings, token usage, model costs, and a detailed audit of the repair loop.
- **Validation Events:** Real-time logging of diagnostic discovery and repair attempts.
- **Evidence Tracing:** Process contracts include trace links that map contract elements back to specific excerpts in the source text or clarification answers.

## Static Analysis

Kotlin code is checked by three complementary tools:
- **detekt** and **ktlint** run on every PR via `bazelisk test //...` — fast, style/complexity-focused, configured in `detekt.yml` and `.editorconfig`.
- **SonarCloud** runs on every PR as a required check. It performs deep code analysis for the backend, linter, and web sub-projects, and pushes results into the [SonarCloud dashboard](https://sonarcloud.io/).

## Testing

### Automated Tests

Run all unit and integration tests (excluding live LLM tests):

```bash
bazelisk test //...
```

### Live LLM Smoke Tests

To manually verify that the LLM correctly extracts process contract vocabulary items (such as task kinds, start/end events, and gateways), run the `ContractVocabularySmokeTest` suite with one supported live provider profile. Use the `--test_output=streamed` flag to display live execution logs, token usage, and model cost details in the terminal:

```bash
ANTHROPIC_API_KEY=sk-ant-... SPRING_PROFILES_ACTIVE=anthropic \
  bazelisk test --test_tag_filters=manual,live-llm \
  --test_env=ANTHROPIC_API_KEY --test_env=SPRING_PROFILES_ACTIVE \
  //src/test:ContractVocabularySmokeTest --test_output=streamed
```

Set `SPRING_PROFILES_ACTIVE` to a provider profile: `anthropic`, `openai`, `gemini`, `mistral`, `deepseek`, or `llama`.

To manually verify the full BPMN pipeline, run the `LiveLlmFullPipelineSmokeTest` suite with one supported live provider profile:

```bash
ANTHROPIC_API_KEY=sk-ant-... SPRING_PROFILES_ACTIVE=anthropic \
  bazelisk test --test_tag_filters=manual,live-llm \
  --test_env=ANTHROPIC_API_KEY --test_env=SPRING_PROFILES_ACTIVE \
  //src/test:LiveLlmFullPipelineSmokeTest --test_output=streamed
```

This full-pipeline smoke test exercises the employee-onboarding sample end to end and is kept separate from the contract-vocabulary suite.

These tests are gated via Bazel's `manual` tag to prevent accidental LLM invocations during standard builds.

## Project Structure
- `src/`: Kotlin/JVM application (Spring Boot + Embabel).
- `linter/`: Pkl-authored rule catalog (`linter/pkl/`) consumed by the in-process rule engine.
- `docs/`: In-depth documentation in the consolidated [Architecture doc](docs/architecture.md) (covers the pipeline, hexagonal design, GOAP lifecycle, agent overview, and module map) plus the [Operator Guide](docs/operator-guide.md). Writing a new rule? See [`linter/docs/rule-authoring-guide.md`](linter/docs/rule-authoring-guide.md).

## Contributing

We follow [Conventional Commits](https://www.conventionalcommits.org/). Please refer to the [Linter README](linter/README.md) for details on adding new rules.
