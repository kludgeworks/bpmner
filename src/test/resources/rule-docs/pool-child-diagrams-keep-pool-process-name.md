---
markdownlint-disable: MD013
---

# pool-child-diagrams-keep-pool-process-name

- **Name**: Child Diagrams Keep Pool Process Name
- **Category**: Pool
- **Severity**: WARNING
- **Target Elements**: `bpmn:Participant`

## Intent

Keep child-level pool labels aligned with the upper-level process name.

## Modeller Guidance

When a child diagram elaborates a subprocess, keep the pool label as the upper-level process name rather than renaming it to the subprocess.

## AI Guidance

Compare parent and child diagram context when available; a single BPMN XML document does not reliably prove cross-level naming intent.

## Diagnostic Messages

- `default`: Child diagram pool should keep the parent process name

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
