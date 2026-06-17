---
markdownlint-disable: MD013
---

# gen-business-clarity-over-technical-detail

- **Name**: Business Clarity Over Technical Detail
- **Category**: General
- **Severity**: INFO
- **Target Elements**: `bpmn:Definitions`, `bpmn:Process`, `bpmn:FlowElement`
- **Cookbook Code**: `GEN-02`

## Intent

Keep BPMN diagrams focused on business behavior rather than implementation mechanics.

## Modeller Guidance

Prefer clear business outcomes, responsibilities, and decisions over technical implementation details that obscure the process.

## AI Guidance

Review labels and structure for business readability. Flag technical detail only when it dominates or obscures business intent.

## Diagnostic Messages

- `default`: Business clarity over technical detail requires contextual review

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
