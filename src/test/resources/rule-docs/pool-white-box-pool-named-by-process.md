---
markdownlint-disable: MD013
---

# pool-white-box-pool-named-by-process

- **Name**: White Box Pool Named By Process
- **Category**: Pool
- **Severity**: WARNING
- **Target Elements**: `bpmn:Participant`

## Intent

Name white-box pools after the process they expose.

## Modeller Guidance

Use the process name as the label of a white-box pool, not an organization, department, or role.

## AI Guidance

For pools with a process reference, compare the participant label with the referenced process label when both are present.

## Diagnostic Messages

- `default`: White-box pool name should match the referenced process name

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
