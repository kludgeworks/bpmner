---
markdownlint-disable: MD013
---

# evt-error-end-boundary-pair

- **Name**: Error End Boundary Pair
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:EndEvent`

## Intent

Ensure error end events propagate to matching parent boundary error handlers.

## Modeller Guidance

Place error end events inside subprocesses and provide a matching error boundary event on the parent subprocess.

## AI Guidance

Detect error end events outside subprocesses or without a matching parent boundary error event using the same error name or code.

## Diagnostic Messages

- `default`: Error end event must match an error boundary event on its parent subprocess
- `missingBoundary`: Error end event must match an error boundary event on its parent subprocess
- `outsideSubprocess`: Error end event must be placed inside a subprocess

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
