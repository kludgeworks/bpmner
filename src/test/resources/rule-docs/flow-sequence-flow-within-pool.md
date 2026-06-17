---
markdownlint-disable: MD013
---

# flow-sequence-flow-within-pool

- **Name**: Sequence Flow Within Pool
- **Category**: Flow
- **Severity**: ERROR
- **Target Elements**: `bpmn:SequenceFlow`

## Intent

Keep sequence flows within a single pool.

## Modeller Guidance

Use sequence flow only within the same pool; use message flow for communication between pools.

## AI Guidance

Detect sequence flows whose source and target resolve to different pools.

## Diagnostic Messages

- `default`: Sequence flow must not cross pool boundaries

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
