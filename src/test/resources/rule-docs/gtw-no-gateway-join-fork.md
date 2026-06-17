---
markdownlint-disable: MD013
---

# gtw-no-gateway-join-fork

- **Name**: No Gateway Join Fork
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`

## Intent

Prevent a single gateway from acting as both a join and a fork.

## Modeller Guidance

Use separate converging and diverging gateways instead of one gateway with multiple incoming and multiple outgoing flows.

## AI Guidance

Detect exclusive, inclusive, or parallel gateways with at least two incoming and at least two outgoing flows.

## Diagnostic Messages

- `default`: Gateway acts as both join and fork; split into separate converging and diverging gateways

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `splitJoinForkGateway`
