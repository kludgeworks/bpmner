---
markdownlint-disable: MD013
---

# evt-event-state-name

- **Name**: Event State Name
- **Category**: Event
- **Severity**: WARNING
- **Target Elements**: `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`

## Intent

Encourage event labels to describe states or happenings rather than process actions.

## Modeller Guidance

Name events as things that happen or states that are reached, not as actions performed by the process.

## AI Guidance

Detect event labels whose first token is POS-tagged as a verb and suggest state-style wording.

## Diagnostic Messages

- `default`: Event name should describe a state/happening, not an action

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
