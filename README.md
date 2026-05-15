# bpmner

Generates valid, semantically-grounded BPMN 2.0 XML from plain-language business process descriptions.

`bpmner` is more than a simple BPMN generator. It is a high-integrity modeling assistant that bridges the gap between ambiguous human language and technical process standards. Built on the [Embabel](https://github.com/embabel/embabel-agent) agentic framework, it employs a defense-in-depth pipeline to ensure every generated diagram is not only syntactically correct but also semantically aligned with user intent.

## Core Pillars

### 1. Semantic Integrity (The Guardrail Pipeline)
Traditional LLM generation often suffers from "hallucinations" or missing requirements. `bpmner` mitigates this with a unique three-stage guardrail system:
- **Readiness Assessment:** Analyzes the input for modeling suitability. If the description is too vague (missing triggers, end states, or activities), the system blocks generation and initiates an **Interactive Clarification Flow** to gather missing facts.
- **Process Contract Extraction:** Derives a structured "contract" from the source text and clarifications. This contract serves as the source of truth, grounding every subsequent generation step in evidence.
- **Semantic Alignment:** After generation, the system performs a terminal check comparing the BPMN elements against the Process Contract. Any invented tasks or missing branches are detected and reported, preventing ungrounded models from being delivered.

### 2. Technical Quality (XSD & bpmn-lint)
Every diagram is strictly validated against:
- The official **BPMN 2.0 XSD**.
- A custom **bpmn-lint plugin** with 27 specialized rules enforcing industry best practices (naming conventions, connectivity, and structural logic).

### 3. Deterministic Repair
When validation fails, `bpmner` doesn't just "try again." It uses a **local-first repair loop** that attempts to fix technical issues (like ID mismatches or simple naming violations) using deterministic Kotlin and TypeScript code before falling back to the LLM for more complex semantic refactoring.

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
The application supports both an interactive shell and one-shot generation.

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

## Project Structure
- `src/`: Kotlin/JVM application (Spring Boot + Embabel).
- `linter/`: TypeScript `bpmn-lint` plugin and custom rule catalog.
- `docs/`: In-depth documentation on [Pipeline Architecture](docs/pipeline-architecture.md) and [Hexagonal Design](docs/hexagonal-architecture.md).

## Contributing
We follow [Conventional Commits](https://www.conventionalcommits.org/). Please refer to the [Linter README](linter/README.md) for details on adding new rules.
