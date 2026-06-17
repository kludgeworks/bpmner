---
markdownlint-disable: MD013
---

# act-mi-task-annotation

- **Name**: MI Task Annotation
- **Category**: Activity
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`

## Intent

Ensure multi-instance activities document the set of items being iterated.

## Modeller Guidance

Attach a text annotation to each multi-instance task or subprocess that explains the item set, for example For each passenger.

## AI Guidance

Detect multi-instance activities without an associated annotation containing iteration-set wording such as each, every, or per.

## Diagnostic Messages

- `default`: Multi-instance activity's annotation must name the item set with each, every, or per

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
