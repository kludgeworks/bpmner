---
markdownlint-disable: MD013
---

# gtw-diverging-flow-names

- **Name**: Diverging Flow Names
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ComplexGateway`

## Intent

Require outcome labels on diverging gateway branches.

## Modeller Guidance

Name outgoing flows from diverging exclusive, inclusive, and complex gateways with short outcome labels.

## AI Guidance

Detect unnamed outgoing sequence flows from diverging exclusive, inclusive, or complex gateways.

## Diagnostic Messages

- `default`: Sequence flow from diverging gateway must have an outcome label

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
