---
markdownlint-disable: MD013
---

# def-duplicate-ids

- **Name**: Duplicate IDs
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:FlowNode`, `bpmn:SequenceFlow`

## Intent

Ensure node and sequence-flow identifiers are unique after trimming whitespace.

## Modeller Guidance

Give every element and flow a unique id.

## AI Guidance

Generate unique ids for every node and sequenceFlow.

## Diagnostic Messages

- `def-duplicate-edge-id`: Sequence flow ids must be unique.
- `def-duplicate-node-id`: Node ids must be unique.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
