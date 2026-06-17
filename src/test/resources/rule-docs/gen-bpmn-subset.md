---
markdownlint-disable: MD013
---

# gen-bpmn-subset

- **Name**: BPMN Subset
- **Category**: General
- **Severity**: ERROR
- **Target Elements**: `bpmn:Choreography`, `bpmn:ChoreographyTask`, `bpmn:SubChoreography`, `bpmn:CallChoreography`, `bpmn:Conversation`, `bpmn:ConversationLink`, `bpmn:ConversationAssociation`, `bpmn:Transaction`, `bpmn:CompensateEventDefinition`, `bpmn:EscalationEventDefinition`

## Intent

Keep models within the supported BPMN subset.

## Modeller Guidance

Use only the BPMN elements described in the supported BPMN subset and avoid unsupported exotic BPMN constructs.

## AI Guidance

Detect discouraged BPMN types that are outside the supported subset and propose supported replacements.

## Diagnostic Messages

- `default`: Element type is outside the supported BPMN subset

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
