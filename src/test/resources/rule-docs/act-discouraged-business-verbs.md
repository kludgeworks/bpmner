---
markdownlint-disable: MD013
---

# act-discouraged-business-verbs

- **Name**: Discouraged Business Verbs
- **Category**: Activity
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`

## Intent

Avoid generic activity verbs that hide the real business action.

## Modeller Guidance

Replace vague leading verbs with a more specific business verb.

## AI Guidance

Detect activity labels whose first word is on the discouraged generic verb list.

## Diagnostic Messages

- `default`: Activity label starts with a discouraged generic verb; prefer a more specific business verb

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
