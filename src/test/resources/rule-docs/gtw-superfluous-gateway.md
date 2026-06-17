---
markdownlint-disable: MD013
---

# gtw-superfluous-gateway

- **Name**: Superfluous Gateway
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`

## Intent

Remove passthrough gateways that carry no routing decision.

## Modeller Guidance

Avoid gateways with exactly one incoming and one outgoing flow because they do not split or merge control flow.

## AI Guidance

Detect exclusive, inclusive, or parallel gateways with a single incoming and single outgoing flow.

## Diagnostic Messages

- `default`: Gateway has a single incoming and single outgoing flow and can be removed

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `bypassGateway`
