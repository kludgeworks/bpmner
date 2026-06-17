---
markdownlint-disable: MD013
---

# assoc-required-annotation-association

- **Name**: Required Annotation Association
- **Category**: Association
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`

## Intent

Require explicit association links from loop and multi-instance activities to their explanatory annotations.

## Modeller Guidance

Use an association to link required text annotations to loop or multi-instance tasks and subprocesses.

## AI Guidance

Detect loop or multi-instance activities that do not have any associated text annotation.

## Diagnostic Messages

- `default`: Loop or multi-instance activity must be linked to a text annotation via association

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
