---
markdownlint-disable: MD013
---

# gtw-gateway-no-work-label

- **Name**: Gateway No Work Label
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ComplexGateway`

## Intent

Keep gateway labels focused on decision conditions rather than work execution.

## Modeller Guidance

Model work as an activity before the gateway; use the gateway only to evaluate the resulting condition.

## AI Guidance

Detect diverging gateway labels that start with action verbs or configured work verbs.

## Diagnostic Messages

- `default`: Gateway label should describe a decision condition, not perform work

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `clearName`
