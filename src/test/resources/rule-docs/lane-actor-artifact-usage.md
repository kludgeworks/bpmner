---
markdownlint-disable: MD013
---

# lane-actor-artifact-usage

- **Name**: Actor Artifact Usage
- **Category**: Lane
- **Severity**: INFO
- **Target Elements**: `bpmn:Artifact`

## Intent

Treat Actor custom artifacts as lane clarification only.

## Modeller Guidance

Use an Actor custom artifact inside a lane only to clarify who performs the lane activities; it does not replace the lane.

## AI Guidance

Do not infer additional participants, lanes, control flow, or responsibility semantics from Actor artifacts.

## Diagnostic Messages

- `default`: Actor artifacts are documentation only and require modelling context

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
