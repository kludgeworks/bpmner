---
markdownlint-disable: MD013
---

# evt-start-no-incoming

- **Name**: Start No Incoming
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`

## Intent

Enforce BPMN start-event structure.

## Modeller Guidance

Start events initiate the process and must not have incoming sequence flows.

## AI Guidance

Validate that every start event has zero incoming sequence flows.

## Diagnostic Messages

- `default`: Start event must not have incoming sequence flow

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `deleteIncomingFlows`
