---
markdownlint-disable: MD013
---

# msg-message-flow-name-pattern

- **Name**: Message Flow Name Pattern
- **Category**: Message
- **Severity**: WARNING
- **Target Elements**: `bpmn:MessageFlow`

## Intent

Encourage noun-based message naming over action phrasing.

## Modeller Guidance

Label message flows with the message name, such as Approval confirmation, rather than an action such as Send approval.

## AI Guidance

Detect message flow labels that start with a verb or auxiliary token.

## Diagnostic Messages

- `default`: Message flow name should describe the message, not an action

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
