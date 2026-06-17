---
markdownlint-disable: MD013
---

# gtw-fake-join

- **Name**: Fake Join
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:UserTask`, `bpmn:ServiceTask`, `bpmn:SendTask`, `bpmn:ReceiveTask`, `bpmn:ManualTask`, `bpmn:BusinessRuleTask`, `bpmn:ScriptTask`

## Intent

Ensure converging flows pass through an explicit gateway rather than directly into a task.

## Modeller Guidance

When two or more flows merge before work continues, model the merge with a converging gateway before the task.

## AI Guidance

Detect task elements with two or more incoming sequence flows and no explicit converging gateway.

## Diagnostic Messages

- `default`: Task has multiple incoming flows without an explicit converging gateway

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `insertConvergingGateway`
