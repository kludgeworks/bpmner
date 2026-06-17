---
markdownlint-disable: MD013
---

# def-dangling-edges

- **Name**: Dangling Edges
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:SequenceFlow`

## Intent

Ensure every sequence flow connects existing BPMN nodes and does not self-reference.

## Modeller Guidance

Connect each flow to two distinct elements that exist in the process.

## AI Guidance

Validate sequenceFlow sourceRef and targetRef against node ids before returning BPMN.

## Diagnostic Messages

- `def-dangling-source`: Sequence flow sourceRef must match an existing node id.
- `def-dangling-target`: Sequence flow targetRef must match an existing node id.
- `def-self-reference`: Sequence flow sourceRef and targetRef must be different.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
