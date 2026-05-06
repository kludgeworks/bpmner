# bpmner

Generates valid BPMN 2.0 XML from a plain-language business process description. Built on the [Embabel](https://github.com/embabel/embabel-agent) agent framework: an LLM produces a typed process definition, which is rendered to XML and validated against the BPMN 2.0 XSD and 27 custom bpmn-lint rules. Validation errors are fed back to the LLM for correction (up to `maxAttempts` rounds).

## Prerequisites

- Bazel 8.6.0 (pinned in `.bazelversion`) — install via [Bazelisk](https://github.com/bazelbuild/bazelisk)
- An LLM API key (Anthropic or GitHub Models / OpenAI)

No Node.js or npm required at runtime — the bpmn-lint bundle is compiled into the jar.

## Build

```bash
bazel build //src:bpmner_app
```

## Run

Pick a provider profile and export the matching key.

**Anthropic:**
```bash
export ANTHROPIC_API_KEY="sk-ant-..."
bazel run //src:bpmner_app -- --spring.profiles.active=anth \
  --process-file=bpmner/toast-process.txt --output=toast.bpmn
```

**GitHub Models (OpenAI-compatible):**
```bash
export GITHUB_TOKEN="ghp_..."
bazel run //src:bpmner_app -- --spring.profiles.active=gh \
  --process-file=bpmner/toast-process.txt --output=toast.bpmn
```

Pass `--process "your description here"` instead of `--process-file` to supply the description inline.

For arbitrary local files, prefer an absolute filesystem path:

```bash
bazel run //src:bpmner_app -- --spring.profiles.active=anth \
  --process-file=/absolute/path/to/process.txt --output=process.bpmn
```

Bundled sample inputs declared in `bazelrun_data` can be loaded as Bazel runfiles using workspace-relative paths such as `bpmner/toast-process.txt`. Arbitrary local files are not automatically added to Bazel runfiles.

## Test

```bash
# Unit tests (fast, no API key needed)
bazel test //src/test/...

# Integration tests (require API key)
bazel test --test_tag_filters=integration //src/test/...
```

## Configuration

Key properties in `src/main/resources/application.yaml`:

| Property | Default | Description |
|----------|---------|-------------|
| `bpmner.max-attempts` | `5` | Maximum LLM correction rounds |
| `bpmner.logging.dir` | `logs` | Directory for per-run timestamped log files |
| `bpmner.logging.file` | (unset) | Optional explicit full log file path override |
| `bpmner.logging.dump-artifacts` | `false` | Emit truncated outline/definition/XML artifact snapshots in debug logs |
| `bpmner.model` | (auto) | Override the LLM model name |

Profile configs live in `application-anthropic.yaml` and `application-github.yaml`.

## Structure

```
src/          Kotlin/JVM application (Spring Boot + Embabel)
linter/       TypeScript bpmn-lint plugin (27 custom rules, bundled at build time)
tools/kotlin/ Bazel Kotlin toolchain config
```
