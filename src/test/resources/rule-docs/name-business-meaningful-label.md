---
markdownlint-disable: MD013
---

# name-business-meaningful-label

- **Name**: Business Meaningful Label
- **Category**: Name
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`, `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`, `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`, `bpmn:ComplexGateway`, `bpmn:DataObjectReference`, `bpmn:DataStoreReference`

## Intent

Encourage business-readable labels over technical identifiers.

## Modeller Guidance

Choose names that are meaningful to business stakeholders and avoid technical shorthand, code names, and implementation identifiers.

## AI Guidance

Detect labels containing technical patterns such as underscores, slash paths, alphanumeric codes, or configured technical tokens.

## Diagnostic Messages

- `default`: Label appears technical/cryptic; prefer business-meaningful wording

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `expandAbbreviations`
