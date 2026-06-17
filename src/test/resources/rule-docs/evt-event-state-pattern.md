---
markdownlint-disable: MD013
---

# evt-event-state-pattern

- **Name**: Event State Pattern
- **Category**: Event
- **Severity**: WARNING
- **Target Elements**: `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`

## Intent

Encourage event labels to follow noun plus state or result wording.

## Modeller Guidance

Name events with a noun and a clear resulting state, such as Request approved or Order received.

## AI Guidance

Detect event labels that lack both a noun or proper noun and a state-like token such as an adjective or past participle.

## Diagnostic Messages

- `default`: Event name should follow a noun + state/result pattern \(e.g. Request approved\)

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
