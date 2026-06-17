---
markdownlint-disable: MD013
---

# def-task-payloads

- **Name**: Task Payloads
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:SendTask`, `bpmn:ReceiveTask`, `bpmn:BusinessRuleTask`

## Intent

Ensure task payload references are present and resolve to known catalog entries where applicable.

## Modeller Guidance

Reference an existing message or decision from specialized tasks.

## AI Guidance

Set messageRef on send/receive tasks and decisionRef on business rule tasks.

## Diagnostic Messages

- `def-invalid-task-message-ref`: Task messageRef must match a message catalog id.
- `def-missing-decision-ref`: Business rule tasks must declare decisionRef.
- `def-missing-message-ref`: Send and receive tasks must declare messageRef.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
