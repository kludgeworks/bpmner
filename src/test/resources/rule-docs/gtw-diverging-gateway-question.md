---
markdownlint-disable: MD013
---

# gtw-diverging-gateway-question

- **Name**: Diverging Gateway Question
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`

## Intent

Encourage question-style naming on diverging exclusive and inclusive gateways.

## Modeller Guidance

Name diverging exclusive and inclusive gateways with a question that expresses the decision.

## AI Guidance

Detect diverging exclusive and inclusive gateways with missing names or names that are not interrogative.

## Diagnostic Messages

- `default`: Diverging exclusive/inclusive gateway should be named as a question

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
