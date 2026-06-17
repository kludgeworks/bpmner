---
markdownlint-disable: MD013
---

# lane-lane-labels-business-roles-performers

- **Name**: Lane Labels Business Roles Performers
- **Category**: Lane
- **Severity**: WARNING
- **Target Elements**: `bpmn:Lane`

## Intent

Require lane labels that identify the responsible business role or performer.

## Modeller Guidance

Name each lane by the business role or performer responsible for the activities in that lane.

## AI Guidance

Deterministically require lane labels to be present; judging whether the label is the correct role requires business context.

## Diagnostic Messages

- `default`: Lane must have a business role or performer name

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
