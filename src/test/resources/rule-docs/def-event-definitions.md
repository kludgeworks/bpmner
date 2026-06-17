---
markdownlint-disable: MD013
---

# def-event-definitions

- **Name**: Event Definitions
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`, `bpmn:EndEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:BoundaryEvent`

## Intent

Ensure BPMN event definitions are present, structurally valid, and resolve to catalog entries.

## Modeller Guidance

Choose the correct event trigger and attach boundary events to activities.

## AI Guidance

Populate event definitions and catalog refs consistently for every event node.

## Diagnostic Messages

- `def-invalid-attached-to`: Boundary event attachedToRef must match an existing node id.
- `def-invalid-error-ref`: Error event definitions must reference an existing error.
- `def-invalid-escalation-ref`: Escalation event definitions must reference an existing escalation.
- `def-invalid-message-ref`: Message event definitions must reference an existing message.
- `def-invalid-signal-ref`: Signal event definitions must reference an existing signal.
- `def-missing-attached-to`: Boundary events must declare attachedToRef.
- `def-missing-event-def`: Intermediate and boundary events must declare an event definition.
- `def-missing-timer-expr`: Timer event expression must not be blank.
- `def-non-task-attached-to`: Boundary events must attach to an activity.

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
