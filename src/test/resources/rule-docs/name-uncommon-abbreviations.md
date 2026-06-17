---
markdownlint-disable: MD013
---

# name-uncommon-abbreviations

- **Name**: Uncommon Abbreviations
- **Category**: Name
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`, `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`, `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`, `bpmn:ComplexGateway`, `bpmn:DataObjectReference`, `bpmn:DataStoreReference`

## Intent

Reduce ambiguity from obscure abbreviations in BPMN labels.

## Modeller Guidance

Avoid uncommon abbreviations in labels, or explain them with an annotation or glossary.

## AI Guidance

Detect uppercase abbreviations that are not on the common acronym allow-list and ask for a clearer label or explanation.

## Diagnostic Messages

- `default`: Avoid uncommon abbreviations in labels or explain them via annotation/glossary

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `expandAbbreviations`

### Replacements
- `AUTH` → `authentication`
- `CFG` → `configuration`
- `DOC` → `document`
- `ITBL` → `itinerary block`
- `MSG` → `message`
- `REQ` → `request`
- `RESP` → `response`
