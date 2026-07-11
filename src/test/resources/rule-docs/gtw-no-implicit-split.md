---
markdownlint-disable: MD013
---

# gtw-no-implicit-split

- **Name**: No Implicit Split
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:FlowNode`

## Intent

Require an explicit gateway when control flow splits.

## Modeller Guidance

Place a gateway before an activity or event that has more than one outgoing sequence flow.

## AI Guidance

Detect non-gateway flow nodes with multiple outgoing sequence flows; do not auto-fix because the gateway semantics cannot be inferred safely.

## Diagnostic Messages

- `default`: Non-gateway flow node has multiple outgoing flows; add an explicit gateway

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
