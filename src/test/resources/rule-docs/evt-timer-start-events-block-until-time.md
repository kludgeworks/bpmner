---
markdownlint-disable: MD013
---

# evt-timer-start-events-block-until-time

- **Name**: Timer Start Events Block Until Time
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`

## Intent

Ensure timer start events define the time condition that starts the process.

## Modeller Guidance

Use a timer start event only when the process waits for a specific date, duration, or cycle before starting.

## AI Guidance

Detect timer start events with no timer expression or with more than one timer expression. General start-event incoming-flow checks are handled by the start-no-incoming rule.

## Diagnostic Messages

- `default`: Timer start event must define exactly one timer expression
- `missingTimerExpression`: Timer start event must define a date, duration, or cycle
- `multipleTimerExpressions`: Timer start event must define only one timer expression

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
