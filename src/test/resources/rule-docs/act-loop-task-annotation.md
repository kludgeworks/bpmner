---
markdownlint-disable: MD013
---

# act-loop-task-annotation

- **Name**: Loop Task Annotation
- **Category**: Activity
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`

## Intent

Ensure loop activities document the condition that stops repetition.

## Modeller Guidance

Attach a text annotation to each loop task or subprocess that explains the loop condition, for example Loop until the condition is met.

## AI Guidance

Detect standard loop activities without an associated annotation whose text contains loop intent and a condition such as until, while, unless, or till.

## Diagnostic Messages

- `default`: Loop activity's annotation must express the loop condition with until, while, unless, or till

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
