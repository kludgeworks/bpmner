---
markdownlint-disable: MD013
---

# msg-message-flow-across-pools

- **Name**: Message Flow Across Pools
- **Category**: Message
- **Severity**: ERROR
- **Target Elements**: `bpmn:MessageFlow`

## Intent

Ensure message flow models inter-participant communication.

## Modeller Guidance

Use message flows only between different pools or participants, not within a single pool.

## AI Guidance

Detect message flows whose source and target resolve to the same pool or cannot be mapped to valid pools.

## Diagnostic Messages

- `default`: Message flow must connect elements in different pools

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
