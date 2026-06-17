---
markdownlint-disable: MD013
---

# gtw-event-based-direct-events

- **Name**: Event Based Direct Events
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:EventBasedGateway`

## Intent

Enforce event-based gateway semantics.

## Modeller Guidance

Use event-based gateways only when the process waits for events, and connect outgoing flows directly to intermediate catch events or receive tasks.

## AI Guidance

Detect event-based gateway outgoing flows that target anything other than an intermediate catch event or receive task.

## Diagnostic Messages

- `default`: Event-based gateway must connect directly to intermediate catch events or receive tasks

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
