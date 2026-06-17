---
markdownlint-disable: MD013
---

# data-no-type-words-in-data-name

- **Name**: No Type Words In Data Name
- **Category**: Data
- **Severity**: ERROR
- **Target Elements**: `bpmn:DataObject`, `bpmn:DataStore`

## Intent

Keep data element names business-oriented and noun-based.

## Modeller Guidance

Name data objects and data stores with business noun phrases, without redundant BPMN type words such as activity, process, or event.

## AI Guidance

Detect data object and data store names that include discouraged BPMN type words.

## Diagnostic Messages

- `default`: Data element name must be a business noun phrase, not an element-type label

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `stripTypeWords`
