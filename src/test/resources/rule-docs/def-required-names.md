---
markdownlint-disable: MD013
---

# def-required-names

- **Name**: Required Names
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:FlowNode`

## Intent

Ensure BPMN elements that require business-readable labels have names.

## Modeller Guidance

Name activities, events, and gateways when the notation requires a label.

## AI Guidance

Populate name fields for nodes that require labels under the BPMN naming policy.

## Diagnostic Messages

- `def-missing-name`: Required BPMN element name is missing.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
