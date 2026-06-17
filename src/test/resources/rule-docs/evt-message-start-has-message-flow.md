---
markdownlint-disable: MD013
---

# evt-message-start-has-message-flow

- **Name**: Message Start Has Message Flow
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`

## Intent

Ensure message-start semantics are modeled as inter-pool communication.

## Modeller Guidance

When a process starts through a message start event, model the incoming message flow from the external participant.

## AI Guidance

Detect message start events that do not have an incoming message flow from another pool.

## Diagnostic Messages

- `default`: Message start event must have an incoming message flow from another pool

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
