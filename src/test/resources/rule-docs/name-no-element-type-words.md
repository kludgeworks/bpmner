---
markdownlint-disable: MD013
---

# name-no-element-type-words

- **Name**: No Element Type Words
- **Category**: Name
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`, `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`, `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`, `bpmn:ComplexGateway`, `bpmn:DataObjectReference`, `bpmn:DataStoreReference`

## Intent

Avoid redundant BPMN element type words in labels.

## Modeller Guidance

Do not include words such as activity, process, or event in element names because the BPMN shape already indicates the type.

## AI Guidance

Detect named BPMN elements whose labels include redundant element type words.

## Diagnostic Messages

- `default`: Element name must not include its BPMN element type

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `stripTypeWords`
