---
markdownlint-disable: MD013
---

# evt-link-event-pairing

- **Name**: Link Event Pairing
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`

## Intent

Ensure link intermediate events are named and paired correctly.

## Modeller Guidance

Use throwing and catching link intermediate events in matched pairs with the same reference name in the same scope.

## AI Guidance

Detect link events without names or without a named throw/catch counterpart in the same scope.

## Diagnostic Messages

- `default`: Link event must have a named throw/catch counterpart in the same scope
- `missingCounterpart`: Link event must have a named throw/catch counterpart in the same scope
- `missingName`: Link event must have a name and a matching pair in the same scope

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
