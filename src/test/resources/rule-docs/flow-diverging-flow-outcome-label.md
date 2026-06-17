---
markdownlint-disable: MD013
---

# flow-diverging-flow-outcome-label

- **Name**: Diverging Flow Outcome Label
- **Category**: Flow
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ComplexGateway`

## Intent

Encourage explicit outcome labels on flows from diverging decision gateways.

## Modeller Guidance

Name flows leaving diverging exclusive, inclusive, or complex gateways with outcome conditions such as Valid or Not eligible.

## AI Guidance

Detect outgoing sequence flows from diverging exclusive, inclusive, or complex gateways that have empty or missing labels.

## Diagnostic Messages

- `default`: Sequence flow from diverging gateway should use an outcome condition label

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
