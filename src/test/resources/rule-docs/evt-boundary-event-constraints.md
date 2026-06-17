---
markdownlint-disable: MD013
---

# evt-boundary-event-constraints

- **Name**: Boundary Event Constraints
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:BoundaryEvent`

## Intent

Enforce valid boundary event attachment and flow cardinality.

## Modeller Guidance

Attach boundary events to tasks or subprocesses, do not give them incoming sequence flow, and use exactly one outgoing sequence flow.

## AI Guidance

Detect detached boundary events, boundary events with incoming flow or wrong outgoing count, and non-interrupting error boundary events.

## Diagnostic Messages

- `default`: Boundary event violates attachment or flow constraints
- `detached`: Boundary event must be attached to a task or subprocess
- `errorInterrupting`: Error boundary event must be interrupting
- `incoming`: Boundary event must not have incoming sequence flow
- `outgoing`: Boundary event must have exactly one outgoing sequence flow

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
