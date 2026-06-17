---
markdownlint-disable: MD013
---

# def-required-events

- **Name**: Required Events
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`, `bpmn:EndEvent`

## Intent

Ensure each BPMN definition has at least one start event and one end event.

## Modeller Guidance

Model a clear process start and completion point.

## AI Guidance

Include at least one START_EVENT and one END_EVENT in every generated definition.

## Diagnostic Messages

- `def-missing-end-event`: Definition must contain at least one end event.
- `def-missing-start-event`: Definition must contain at least one start event.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
