---
markdownlint-disable: MD013
---

# evt-intermediate-event-not-action

- **Name**: Intermediate Event Not Action
- **Category**: Event
- **Severity**: WARNING
- **Target Elements**: `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`

## Intent

Ensure intermediate events describe states or happenings rather than work.

## Modeller Guidance

Use intermediate events for things that happen while activities perform the work.

## AI Guidance

Detect intermediate catch or throw event labels that start with a verb or auxiliary and suggest state-style wording or a task.

## Diagnostic Messages

- `default`: Intermediate event name should describe a state, not an action

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
