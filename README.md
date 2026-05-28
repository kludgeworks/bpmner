# bpmner

Generates valid, semantically-grounded BPMN 2.0 XML from plain-language workflow descriptions — business, automated, technical, scientific, or personal.

`bpmner` is more than a simple BPMN generator. It is a high-integrity modeling assistant that bridges the gap between ambiguous human language and technical process standards. Built on the [Embabel](https://github.com/embabel/embabel-agent) agentic framework, it employs a defense-in-depth pipeline to ensure every generated diagram is not only syntactically correct but also semantically aligned with user intent.

## Core Pillars

### 1. Semantic Integrity (The Guardrail Pipeline)
Traditional LLM generation often suffers from "hallucinations" or missing requirements. `bpmner` mitigates this with a unique three-stage guardrail system:
- **Readiness Assessment:** Analyzes the input for modeling suitability. If the description is too vague (missing triggers, end states, or activities), the system blocks generation and initiates an **Interactive Clarification Flow** to gather missing facts.
- **Process Contract Extraction:** Derives a structured "contract" from the source text and clarifications. This contract serves as the source of truth, grounding every subsequent generation step in evidence.
- **Semantic Alignment:** After generation, the system performs a terminal check comparing the BPMN elements against the Process Contract. Any invented tasks or missing branches are detected and reported, preventing ungrounded models from being delivered.

### 2. Technical Quality (XSD & Pkl rule engine)
Every diagram is strictly validated against:
- The official **BPMN 2.0 XSD**.
- A custom **Pkl-authored rule catalog** with specialized rules enforcing industry best practices (naming conventions, connectivity, and structural logic). Rules are evaluated in-process by a Kotlin `RuleEngine`; the legacy GraalJS-hosted `bpmnlint` bridge was retired in #241 phase 2G.

### 3. Deterministic Repair
When validation fails, `bpmner` doesn't just "try again." It uses a **local-first repair loop** that attempts to fix technical issues (like ID mismatches or simple naming violations) using deterministic Kotlin handlers before falling back to the LLM for more complex semantic refactoring.

## Model Routing

Each LLM call routes through a named **role** (e.g. `repair-label`) rather than a hard-coded model. Roles let us match model cost and capability to the task: a label tweak goes to a small fast model, a full rewrite goes to a large one. Deterministic validation (XSD, Pkl rule engine) and `DeterministicTopologyRepairStrategy` never call an LLM — they are pure Kotlin and remain that way by design.

| Role             | Persona                          | Requirement                      |
|------------------|----------------------------------|----------------------------------|
| `generator`      | BPMN Designer                    | Large context, complex reasoning |
| `repair-label`   | BPMN Label Copy Editor           | Small, fast, detail-oriented     |
| `repair-patch`   | BPMN Patch Repair Specialist     | Intermediate, structural logic   |
| `repair-rewrite` | BPMN Full Rewrite Specialist     | Large context, holistic design   |

Roles are also defined for `readiness-assessor` (balanced), `contract-extractor` (high precision), and `alignment-validator` (critical); see `src/main/resources/application*.yaml` for the full list.

**Where to change it.** Persona definitions and role names live in [`BpmnConfig.kt`](src/main/kotlin/dev/groknull/bpmner/core/BpmnConfig.kt). Concrete model assignments live in `src/main/resources/application*.yaml`. Adding a provider is a YAML-only change; adding a new role requires a `BpmnConfig` entry and an entry in every active profile.

**When to promote a task to a larger model.** If a small-tier role starts producing bad fixes — for example `repair-label` mangling capitalization — bump that role to the next tier in the relevant `application-*.yaml`. No code change is needed; the routing key is the role name.

## Getting Started

### Prerequisites
- **Bazel 8.6.0** (pinned in `.bazelversion`) — install via [Bazelisk](https://github.com/bazelbuild/bazelisk).
- **Mise** for environment and tool management.
- An LLM API key (Anthropic, OpenAI, or GitHub Models).

### Build
```bash
bazel build //src:bpmner_app
```

### Run
`bpmner` runs in three modes: a browser-based web UI, an interactive Spring Shell, and one-shot CLI generation.

#### Web Interface
Starts an HTTP server with a live, progress-aware browser UI for submitting process descriptions, watching generation progress over SSE, inspecting intermediate validation snapshots, and downloading the final BPMN XML. The web flow keeps XML in memory rather than writing `.bpmn` files to disk.
```bash
export ANTHROPIC_API_KEY="sk-ant-..."
bazel run //src:bpmner_app -- --spring.profiles.active=anth,web
```
Open `http://localhost:8080` once the server is up.

#### Interactive Shell
Start the shell to use the `generate` command with interactive clarification:
```bash
export ANTHROPIC_API_KEY="sk-ant-..."
bazel run //src:bpmner_app -- --spring.profiles.active=anth
```

#### One-Shot Generation
```bash
bazel run //src:bpmner_app -- --spring.profiles.active=anth \
  --process-file=toast-process.txt --output=toast.bpmn
```

## Observability & Tracing
`bpmner` is built for production observability:
- **Structured Run Summaries:** Every run produces a JSONL summary containing execution timings, token usage, model costs, and a detailed audit of the repair loop.
- **Validation Events:** Real-time logging of diagnostic discovery and repair attempts.
- **Evidence Tracing:** Process contracts include trace links that map contract elements back to specific excerpts in the source text or clarification answers.

## Static Analysis
Kotlin code is checked by three complementary tools:
- **detekt** and **ktlint** run on every PR via `bazel test //...` — fast, style/complexity-focused, configured in `detekt.yml` and `.editorconfig`.
- **SonarCloud** runs on every PR as a required check. It performs deep code analysis for the backend, linter, and web sub-projects, and pushes results into the [SonarCloud dashboard](https://sonarcloud.io/).

## Project Structure
- `src/`: Kotlin/JVM application (Spring Boot + Embabel).
- `linter/`: Pkl-authored rule catalog (`linter/pkl/`) consumed by the in-process rule engine.
- `docs/`: In-depth documentation on [Pipeline Architecture](docs/pipeline-architecture.md), [Hexagonal Design](docs/hexagonal-architecture.md), the [GOAP Lifecycle + Repair Architecture](docs/goap-lifecycle.md), the [Operator Guide](docs/operator-guide.md), and the [Agent Overview](docs/agents.md). Writing a new rule? See [`linter/docs/rule-authoring-guide.md`](linter/docs/rule-authoring-guide.md).

## Contributing
We follow [Conventional Commits](https://www.conventionalcommits.org/). Please refer to the [Linter README](linter/README.md) for details on adding new rules.
