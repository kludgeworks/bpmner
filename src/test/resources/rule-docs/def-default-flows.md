---
markdownlint-disable: MD013
---

# def-default-flows

- **Name**: Default Flows
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:SequenceFlow`, `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`

## Intent

Ensure BPMN default sequence flows are only used from exclusive or inclusive gateways and are unique per source.

## Modeller Guidance

Use at most one default outgoing flow from an exclusive or inclusive gateway.

## AI Guidance

Set isDefault only on a single outgoing flow from an exclusive or inclusive gateway.

## Diagnostic Messages

- `def-default-flow-non-gateway`: Default flow must originate from an exclusive or inclusive gateway.
- `def-multiple-default-flows`: A node can have at most one default flow.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
