---
markdownlint-disable: MD013
---

# gtw-converging-gateway-unnamed

- **Name**: Converging Gateway Unnamed
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`

## Intent

Keep converging gateway labels empty so decision wording stays on the diverging side.

## Modeller Guidance

Do not name converging exclusive, inclusive, or parallel gateways; use a text annotation if convergence needs explanation.

## AI Guidance

Detect converging exclusive, inclusive, or parallel gateways with labels and remove the label when auto-fixing.

## Diagnostic Messages

- `default`: Converging gateway should remain unnamed

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `clearConvergingGatewayName`
