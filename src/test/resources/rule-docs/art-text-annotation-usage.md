---
markdownlint-disable: MD013
---

# art-text-annotation-usage

- **Name**: Text Annotation Usage
- **Category**: Artifact
- **Severity**: WARNING
- **Target Elements**: `bpmn:TextAnnotation`

## Intent

Ensure text annotations are explicitly connected to the element they clarify.

## Modeller Guidance

Use Text Annotation to document clarifications or extra details, and attach it to its target with an association.

## AI Guidance

Require text annotations to have at least one association; loop and multi-instance specificity remains covered by activity and association rules.

## Diagnostic Messages

- `default`: Text annotation must be linked to a BPMN element with an association

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
