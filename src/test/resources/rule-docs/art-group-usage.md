---
markdownlint-disable: MD013
---

# art-group-usage

- **Name**: Group Usage
- **Category**: Artifact
- **Severity**: INFO
- **Target Elements**: `bpmn:Group`

## Intent

Keep BPMN groups as visual, non-semantic containers.

## Modeller Guidance

Use Group to visually group related elements; it does not affect process logic.

## AI Guidance

Treat groups as visual, non-semantic containers. Do not infer control flow, data flow, ownership, or membership semantics from a group.

## Diagnostic Messages

- `default`: Groups are visual containers and require modelling context

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
