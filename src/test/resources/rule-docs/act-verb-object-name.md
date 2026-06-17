---
markdownlint-disable: MD013
---

# act-verb-object-name

- **Name**: Verb Object Name
- **Category**: Activity
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`

## Intent

Make activity labels action-oriented and specific.

## Modeller Guidance

Name activities with a business verb followed by the object being acted on.

## AI Guidance

Detect activity labels that do not start with a verb or that contain fewer than two words.

## Diagnostic Messages

- `default`: Activity name should follow Verb + Object
- `missingVerb`: Activity name should start with a business verb
- `tooShort`: Activity name should follow Verb + Object \(at least two words\)

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
