---
markdownlint-disable: MD013
---

# pool-black-box-pool-named-by-external-entity-or-process

- **Name**: Black Box Pool Named By External Entity Or Process
- **Category**: Pool
- **Severity**: WARNING
- **Target Elements**: `bpmn:Participant`

## Intent

Ensure black-box pools are identifiable external participants.

## Modeller Guidance

Name black-box pools using the external entity, organization, department, system, or external process they represent.

## AI Guidance

For pools without a process reference, deterministically require a non-empty label; semantic entity checks require modelling context.

## Diagnostic Messages

- `default`: Black-box pool must have a name

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
