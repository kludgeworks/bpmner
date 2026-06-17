---
markdownlint-disable: MD013
---

# gen-no-duplicate-diagrams

- **Name**: No Duplicate Diagrams
- **Category**: General
- **Severity**: ERROR
- **Target Elements**: `bpmn:Definitions`
- **Legacy Aliases**: `gen-02-no-duplicate-diagrams`

## Intent

Ensure BPMN documents contain a single diagram for downstream viewer compatibility.

## Modeller Guidance

Keep each BPMN document to one BPMN diagram entry so tools such as bpmn-js can load it reliably.

## AI Guidance

Detect bpmn:Definitions roots containing more than one bpmndi:BPMNDiagram.

## Diagnostic Messages

- `default`: Multiple bpmndi:BPMNDiagram elements found. Only one diagram is allowed for compatibility with viewers like bpmn-js.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
