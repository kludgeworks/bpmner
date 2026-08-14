---
markdownlint-disable: MD013
---

# BPMN Rules

## act-activity-label-capitalization

- **Name**: Activity Label Capitalization
- **Category**: Activity
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`

### Purpose

Keep activity labels in readable sentence case.

### Modeller Guidance

Capitalize the first word of an activity label and keep later words lowercase unless they are acronyms or proper nouns.

### AI Guidance

Detect activity, subprocess, and call activity labels that start lowercase or use title case after the first word.

### Diagnostic Messages

- `default`: Activity label should use sentence case
- `firstWord`: Activity label should start with a capitalized first word
- `sentenceCase`: Activity label should use sentence case after the first word \(except acronyms/proper nouns\)

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `fixSentenceCase`

## act-discouraged-business-verbs

- **Name**: Discouraged Business Verbs
- **Category**: Activity
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`

### Purpose

Avoid generic activity verbs that hide the real business action.

### Modeller Guidance

Replace vague leading verbs with a more specific business verb.

### AI Guidance

Detect activity labels whose first word is on the discouraged generic verb list.

### Diagnostic Messages

- `default`: Activity label starts with a discouraged generic verb; prefer a more specific business verb

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## act-loop-task-annotation

- **Name**: Loop Task Annotation
- **Category**: Activity
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`

### Purpose

Ensure loop activities document the condition that stops repetition.

### Modeller Guidance

Attach a text annotation to each loop task or subprocess that explains the loop condition, for example Loop until the condition is met.

### AI Guidance

Detect standard loop activities without an associated annotation whose text contains loop intent and a condition such as until, while, unless, or till.

### Diagnostic Messages

- `default`: Loop activity's annotation must express the loop condition with until, while, unless, or till

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## act-mi-task-annotation

- **Name**: MI Task Annotation
- **Category**: Activity
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`

### Purpose

Ensure multi-instance activities document the set of items being iterated.

### Modeller Guidance

Attach a text annotation to each multi-instance task or subprocess that explains the item set, for example For each passenger.

### AI Guidance

Detect multi-instance activities without an associated annotation containing iteration-set wording such as each, every, or per.

### Diagnostic Messages

- `default`: Multi-instance activity's annotation must name the item set with each, every, or per

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## act-verb-object-name

- **Name**: Verb Object Name
- **Category**: Activity
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`

### Purpose

Make activity labels action-oriented and specific.

### Modeller Guidance

Name activities with a business verb followed by the object being acted on.

### AI Guidance

Detect activity labels that do not start with a verb or that contain fewer than two words.

### Diagnostic Messages

- `default`: Activity name should follow Verb + Object
- `missingVerb`: Activity name should start with a business verb
- `tooShort`: Activity name should follow Verb + Object \(at least two words\)

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## art-group-usage

- **Name**: Group Usage
- **Category**: Artifact
- **Severity**: INFO
- **Target Elements**: `bpmn:Group`

### Purpose

Keep BPMN groups as visual, non-semantic containers.

### Modeller Guidance

Use Group to visually group related elements; it does not affect process logic.

### AI Guidance

Treat groups as visual, non-semantic containers. Do not infer control flow, data flow, ownership, or membership semantics from a group.

### Diagnostic Messages

- `default`: Groups are visual containers and require modelling context

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## art-text-annotation-usage

- **Name**: Text Annotation Usage
- **Category**: Artifact
- **Severity**: WARNING
- **Target Elements**: `bpmn:TextAnnotation`

### Purpose

Ensure text annotations are explicitly connected to the element they clarify.

### Modeller Guidance

Use Text Annotation to document clarifications or extra details, and attach it to its target with an association.

### AI Guidance

Require text annotations to have at least one association; loop and multi-instance specificity remains covered by activity and association rules.

### Diagnostic Messages

- `default`: Text annotation must be linked to a BPMN element with an association

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## assoc-required-annotation-association

- **Name**: Required Annotation Association
- **Category**: Association
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`

### Purpose

Require explicit association links from loop and multi-instance activities to their explanatory annotations.

### Modeller Guidance

Use an association to link required text annotations to loop or multi-instance tasks and subprocesses.

### AI Guidance

Detect loop or multi-instance activities that do not have any associated text annotation.

### Diagnostic Messages

- `default`: Loop or multi-instance activity must be linked to a text annotation via association

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## data-no-type-words-in-data-name

- **Name**: No Type Words In Data Name
- **Category**: Data
- **Severity**: ERROR
- **Target Elements**: `bpmn:DataObject`, `bpmn:DataStore`

### Purpose

Keep data element names business-oriented and noun-based.

### Modeller Guidance

Name data objects and data stores with business noun phrases, without redundant BPMN type words such as activity, process, or event.

### AI Guidance

Detect data object and data store names that include discouraged BPMN type words.

### Diagnostic Messages

- `default`: Data element name must be a business noun phrase, not an element-type label

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `stripTypeWords`

## def-dangling-edges

- **Name**: Dangling Edges
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:SequenceFlow`

### Purpose

Detect dangling or self-referencing sequence flows that a V1-clean contract cannot produce — a hit indicates a compiler bug, not a modelling error.

### Modeller Guidance

Should never fire on compiler output; a hit is a bug report, not a fix-it.

### AI Guidance

Not modeller-facing guidance — a hit here means the compiler emitted an edge its own contract input forbids.

### Diagnostic Messages

- `def-dangling-source`: Sequence flow sourceRef must match an existing node id.
- `def-dangling-target`: Sequence flow targetRef must match an existing node id.
- `def-self-reference`: Sequence flow sourceRef and targetRef must be different.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## def-default-flows

- **Name**: Default Flows
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:SequenceFlow`, `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`

### Purpose

Ensure BPMN default sequence flows are only used from exclusive or inclusive gateways and are unique per source.

### Modeller Guidance

Use at most one default outgoing flow from an exclusive or inclusive gateway.

### AI Guidance

Set isDefault only on a single outgoing flow from an exclusive or inclusive gateway.

### Diagnostic Messages

- `def-default-flow-non-gateway`: Default flow must originate from an exclusive or inclusive gateway.
- `def-multiple-default-flows`: A node can have at most one default flow.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## def-duplicate-ids

- **Name**: Duplicate IDs
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:FlowNode`, `bpmn:SequenceFlow`

### Purpose

Ensure node and sequence-flow identifiers are unique after trimming whitespace.

### Modeller Guidance

Give every element and flow a unique id.

### AI Guidance

Generate unique ids for every node and sequenceFlow.

### Diagnostic Messages

- `def-duplicate-edge-id`: Sequence flow ids must be unique.
- `def-duplicate-node-id`: Node ids must be unique.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## def-event-definitions

- **Name**: Event Definitions
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`, `bpmn:EndEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:BoundaryEvent`

### Purpose

Ensure BPMN event definitions are present, structurally valid, and resolve to catalog entries.

### Modeller Guidance

Choose the correct event trigger and attach boundary events to activities.

### AI Guidance

Populate event definitions and catalog refs consistently for every event node.

### Diagnostic Messages

- `def-invalid-activity-ref`: Compensate event definition activityRef, when present, must reference an existing task with isForCompensation=true.
- `def-invalid-attached-to`: Boundary event attachedToRef must match an existing node id.
- `def-invalid-error-ref`: Error event definitions must reference an existing error.
- `def-invalid-escalation-ref`: Escalation event definitions must reference an existing escalation.
- `def-invalid-message-ref`: Message event definitions must reference an existing message.
- `def-invalid-signal-ref`: Signal event definitions must reference an existing signal.
- `def-missing-attached-to`: Boundary events must declare attachedToRef.
- `def-missing-event-def`: Intermediate and boundary events must declare an event definition.
- `def-missing-timer-expr`: Timer event expression must not be blank.
- `def-non-task-attached-to`: Boundary events must attach to an activity.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## def-required-events

- **Name**: Required Events
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`, `bpmn:EndEvent`

### Purpose

Ensure each BPMN definition has at least one start event and one end event (existence only — degree constraints are contract-level, ADR-696-1 V3/V4).

### Modeller Guidance

Model a clear process start and completion point.

### AI Guidance

Include at least one START_EVENT and one END_EVENT in every generated definition.

### Diagnostic Messages

- `def-missing-end-event`: Definition must contain at least one end event.
- `def-missing-start-event`: Definition must contain at least one start event.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## def-required-names

- **Name**: Required Names
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:FlowNode`

### Purpose

Ensure BPMN elements that require business-readable labels have names.

### Modeller Guidance

Name activities, events, and gateways when the notation requires a label.

### AI Guidance

Populate name fields for nodes that require labels under the BPMN naming policy.

### Diagnostic Messages

- `def-missing-name`: Required BPMN element name is missing.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## def-task-payloads

- **Name**: Task Payloads
- **Category**: Definition
- **Severity**: ERROR
- **Target Elements**: `bpmn:SendTask`, `bpmn:ReceiveTask`, `bpmn:BusinessRuleTask`

### Purpose

Ensure task payload references are present and resolve to known catalog entries where applicable.

### Modeller Guidance

Reference an existing message or decision from specialized tasks.

### AI Guidance

Set messageRef on send/receive tasks and decisionRef on business rule tasks.

### Diagnostic Messages

- `def-invalid-task-message-ref`: Task messageRef must match a message catalog id.
- `def-missing-decision-ref`: Business rule tasks must declare decisionRef.
- `def-missing-message-ref`: Send and receive tasks must declare messageRef.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-boundary-event-constraints

- **Name**: Boundary Event Constraints
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:BoundaryEvent`

### Purpose

Enforce valid boundary event attachment and flow cardinality.

### Modeller Guidance

Attach boundary events to tasks or subprocesses, do not give them incoming sequence flow, and use exactly one outgoing sequence flow.

### AI Guidance

Detect detached boundary events, boundary events with incoming flow or wrong outgoing count, and non-interrupting error boundary events.

### Diagnostic Messages

- `default`: Boundary event violates attachment or flow constraints
- `detached`: Boundary event must be attached to a task or subprocess
- `errorInterrupting`: Error boundary event must be interrupting
- `incoming`: Boundary event must not have incoming sequence flow
- `outgoing`: Boundary event must have exactly one outgoing sequence flow

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-error-end-boundary-pair

- **Name**: Error End Boundary Pair
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:EndEvent`

### Purpose

Ensure error end events propagate to matching parent boundary error handlers.

### Modeller Guidance

Place error end events inside subprocesses and provide a matching error boundary event on the parent subprocess.

### AI Guidance

Detect error end events outside subprocesses or without a matching parent boundary error event using the same error name or code.

### Diagnostic Messages

- `default`: Error end event must match an error boundary event on its parent subprocess
- `missingBoundary`: Error end event must match an error boundary event on its parent subprocess
- `outsideSubprocess`: Error end event must be placed inside a subprocess

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-event-state-name

- **Name**: Event State Name
- **Category**: Event
- **Severity**: WARNING
- **Target Elements**: `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`

### Purpose

Encourage event labels to describe states or happenings rather than process actions.

### Modeller Guidance

Name events as things that happen or states that are reached, not as actions performed by the process.

### AI Guidance

Detect event labels whose first token is POS-tagged as a verb and suggest state-style wording.

### Diagnostic Messages

- `default`: Event name should describe a state/happening, not an action

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-event-state-pattern

- **Name**: Event State Pattern
- **Category**: Event
- **Severity**: WARNING
- **Target Elements**: `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`

### Purpose

Encourage event labels to follow noun plus state or result wording.

### Modeller Guidance

Name events with a noun and a clear resulting state, such as Request approved or Order received.

### AI Guidance

Detect event labels that lack both a noun or proper noun and a state-like token such as an adjective or past participle.

### Diagnostic Messages

- `default`: Event name should follow a noun + state/result pattern \(e.g. Request approved\)

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-intermediate-event-not-action

- **Name**: Intermediate Event Not Action
- **Category**: Event
- **Severity**: WARNING
- **Target Elements**: `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`

### Purpose

Ensure intermediate events describe states or happenings rather than work.

### Modeller Guidance

Use intermediate events for things that happen while activities perform the work.

### AI Guidance

Detect intermediate catch or throw event labels that start with a verb or auxiliary and suggest state-style wording or a task.

### Diagnostic Messages

- `default`: Intermediate event name should describe a state, not an action

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-link-event-pairing

- **Name**: Link Event Pairing
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`

### Purpose

Ensure link intermediate events are named and paired correctly.

### Modeller Guidance

Use throwing and catching link intermediate events in matched pairs with the same reference name in the same scope.

### AI Guidance

Detect link events without names or without a named throw/catch counterpart in the same scope.

### Diagnostic Messages

- `default`: Link event must have a named throw/catch counterpart in the same scope
- `missingCounterpart`: Link event must have a named throw/catch counterpart in the same scope
- `missingName`: Link event must have a name and a matching pair in the same scope

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-message-start-has-message-flow

- **Name**: Message Start Has Message Flow
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`

### Purpose

Ensure message-start semantics are modeled as inter-pool communication.

### Modeller Guidance

When a process starts through a message start event, model the incoming message flow from the external participant.

### AI Guidance

Detect message start events that do not have an incoming message flow from another pool.

### Diagnostic Messages

- `default`: Message start event must have an incoming message flow from another pool

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## evt-timer-start-events-block-until-time

- **Name**: Timer Start Events Block Until Time
- **Category**: Event
- **Severity**: ERROR
- **Target Elements**: `bpmn:StartEvent`

### Purpose

Ensure timer start events define the time condition that starts the process.

### Modeller Guidance

Use a timer start event only when the process waits for a specific date, duration, or cycle before starting.

### AI Guidance

Detect timer start events with no timer expression or with more than one timer expression. General start-event incoming-flow checks are contract-level (ADR-696-1 V3), not a lint rule.

### Diagnostic Messages

- `default`: Timer start event must define exactly one timer expression
- `missingTimerExpression`: Timer start event must define a date, duration, or cycle
- `multipleTimerExpressions`: Timer start event must define only one timer expression

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## flow-sequence-flow-within-pool

- **Name**: Sequence Flow Within Pool
- **Category**: Flow
- **Severity**: ERROR
- **Target Elements**: `bpmn:SequenceFlow`

### Purpose

Keep sequence flows within a single pool.

### Modeller Guidance

Use sequence flow only within the same pool; use message flow for communication between pools.

### AI Guidance

Detect sequence flows whose source and target resolve to different pools.

### Diagnostic Messages

- `default`: Sequence flow must not cross pool boundaries

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gen-bpmn-subset

- **Name**: BPMN Subset
- **Category**: General
- **Severity**: ERROR
- **Target Elements**: `bpmn:Choreography`, `bpmn:ChoreographyTask`, `bpmn:SubChoreography`, `bpmn:CallChoreography`, `bpmn:Conversation`, `bpmn:ConversationLink`, `bpmn:ConversationAssociation`, `bpmn:Transaction`, `bpmn:DataObject`, `bpmn:DataObjectReference`, `bpmn:DataStore`, `bpmn:DataStoreReference`, `bpmn:DataInputAssociation`, `bpmn:DataOutputAssociation`

### Purpose

Keep models within the supported BPMN subset.

### Modeller Guidance

Use only the BPMN elements described in the supported BPMN subset and avoid unsupported exotic BPMN constructs.

### AI Guidance

Detect discouraged BPMN types that are outside the supported subset and propose supported replacements.

### Diagnostic Messages

- `default`: Element type is outside the supported BPMN subset

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gen-business-clarity-over-technical-detail

- **Name**: Business Clarity Over Technical Detail
- **Category**: General
- **Severity**: INFO
- **Target Elements**: `bpmn:Definitions`, `bpmn:Process`, `bpmn:FlowElement`

### Purpose

Keep BPMN diagrams focused on business behavior rather than implementation mechanics.

### Modeller Guidance

Prefer clear business outcomes, responsibilities, and decisions over technical implementation details that obscure the process.

### AI Guidance

Review labels and structure for business readability. Flag technical detail only when it dominates or obscures business intent.

### Diagnostic Messages

- `default`: Business clarity over technical detail requires contextual review

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gen-no-duplicate-diagrams

- **Name**: No Duplicate Diagrams
- **Category**: General
- **Severity**: ERROR
- **Target Elements**: `bpmn:Definitions`
- **Legacy Aliases**: `gen-02-no-duplicate-diagrams`

### Purpose

Ensure BPMN documents contain a single diagram for downstream viewer compatibility.

### Modeller Guidance

Keep each BPMN document to one BPMN diagram entry so tools such as bpmn-js can load it reliably.

### AI Guidance

Detect bpmn:Definitions roots containing more than one bpmndi:BPMNDiagram.

### Diagnostic Messages

- `default`: Multiple bpmndi:BPMNDiagram elements found. Only one diagram is allowed for compatibility with viewers like bpmn-js.

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gtw-converging-gateway-unnamed

- **Name**: Converging Gateway Unnamed
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`

### Purpose

Keep converging gateway labels empty so decision wording stays on the diverging side.

### Modeller Guidance

Do not name converging exclusive, inclusive, or parallel gateways; use a text annotation if convergence needs explanation.

### AI Guidance

Detect converging exclusive, inclusive, or parallel gateways with labels and remove the label when auto-fixing.

### Diagnostic Messages

- `default`: Converging gateway should remain unnamed

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `clearConvergingGatewayName`

## gtw-diverging-flow-names

- **Name**: Diverging Flow Names
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ComplexGateway`

### Purpose

Require outcome labels on diverging gateway branches.

### Modeller Guidance

Name outgoing flows from diverging exclusive, inclusive, and complex gateways with short outcome labels.

### AI Guidance

Detect unnamed outgoing sequence flows from diverging exclusive, inclusive, or complex gateways.

### Diagnostic Messages

- `default`: Sequence flow from diverging gateway must have an outcome label

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gtw-diverging-gateway-question

- **Name**: Diverging Gateway Question
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`

### Purpose

Encourage question-style naming on diverging exclusive and inclusive gateways.

### Modeller Guidance

Name diverging exclusive and inclusive gateways with a question that expresses the decision.

### AI Guidance

Detect diverging exclusive and inclusive gateways with missing names or names that are not interrogative.

### Diagnostic Messages

- `default`: Diverging exclusive/inclusive gateway should be named as a question

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gtw-event-based-direct-events

- **Name**: Event Based Direct Events
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:EventBasedGateway`

### Purpose

Enforce event-based gateway semantics.

### Modeller Guidance

Use event-based gateways only when the process waits for events, and connect outgoing flows directly to intermediate catch events or receive tasks.

### AI Guidance

Detect event-based gateway outgoing flows that target anything other than an intermediate catch event or receive task.

### Diagnostic Messages

- `default`: Event-based gateway must connect directly to intermediate catch events or receive tasks

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gtw-fake-join

- **Name**: Fake Join
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:UserTask`, `bpmn:ServiceTask`, `bpmn:SendTask`, `bpmn:ReceiveTask`, `bpmn:ManualTask`, `bpmn:BusinessRuleTask`, `bpmn:ScriptTask`

### Purpose

Ensure converging flows pass through an explicit gateway rather than directly into a task.

### Modeller Guidance

When two or more flows merge before work continues, model the merge with a converging gateway before the task.

### AI Guidance

Detect task elements with two or more incoming sequence flows and no explicit converging gateway.

### Diagnostic Messages

- `default`: Task has multiple incoming flows without an explicit converging gateway

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `insertConvergingGateway`

## gtw-gateway-no-work-label

- **Name**: Gateway No Work Label
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ComplexGateway`

### Purpose

Keep gateway labels focused on decision conditions rather than work execution.

### Modeller Guidance

Model work as an activity before the gateway; use the gateway only to evaluate the resulting condition.

### AI Guidance

Detect diverging gateway labels that start with action verbs or configured work verbs.

### Diagnostic Messages

- `default`: Gateway label should describe a decision condition, not perform work

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `clearName`

## gtw-no-gateway-join-fork

- **Name**: No Gateway Join Fork
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`

### Purpose

Prevent a single gateway from acting as both a join and a fork.

### Modeller Guidance

Use separate converging and diverging gateways instead of one gateway with multiple incoming and multiple outgoing flows.

### AI Guidance

Detect exclusive, inclusive, or parallel gateways with at least two incoming and at least two outgoing flows.

### Diagnostic Messages

- `default`: Gateway acts as both join and fork; split into separate converging and diverging gateways

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `splitJoinForkGateway`

## gtw-no-implicit-split

- **Name**: No Implicit Split
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:FlowNode`

### Purpose

Require an explicit gateway when control flow splits.

### Modeller Guidance

Place a gateway before an activity or event that has more than one outgoing sequence flow.

### AI Guidance

Detect non-gateway flow nodes with multiple outgoing sequence flows; do not auto-fix because the gateway semantics cannot be inferred safely.

### Diagnostic Messages

- `default`: Non-gateway flow node has multiple outgoing flows; add an explicit gateway

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gtw-no-inclusive-gateway

- **Name**: No Inclusive Gateway
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:InclusiveGateway`

### Purpose

Keep gateway type choices aligned with BPMN token semantics.

### Modeller Guidance

Prefer explicit exclusive or parallel gateway semantics over inclusive gateways.

### AI Guidance

Enforce deterministic parallel-gateway structure from XML. Treat XOR versus OR versus AND selection as a modelling-intent decision unless explicit structural evidence makes it invalid.

### Diagnostic Messages

- `default`: Inclusive gateways are not permitted by this alternative rule
- `parallelCondition`: Parallel gateway outgoing sequence flow must not be conditional or default-only
- `parallelJoinCardinality`: Parallel converging gateway should have at least two incoming sequence flows
- `parallelSplitCardinality`: Parallel diverging gateway should have at least two outgoing sequence flows

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## gtw-superfluous-gateway

- **Name**: Superfluous Gateway
- **Category**: Gateway
- **Severity**: ERROR
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`

### Purpose

Remove passthrough gateways that carry no routing decision.

### Modeller Guidance

Avoid gateways with exactly one incoming and one outgoing flow because they do not split or merge control flow.

### AI Guidance

Detect exclusive, inclusive, or parallel gateways with a single incoming and single outgoing flow.

### Diagnostic Messages

- `default`: Gateway has a single incoming and single outgoing flow and can be removed

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `bypassGateway`

## lane-actor-artifact-usage

- **Name**: Actor Artifact Usage
- **Category**: Lane
- **Severity**: INFO
- **Target Elements**: `bpmn:Artifact`

### Purpose

Treat Actor custom artifacts as lane clarification only.

### Modeller Guidance

Use an Actor custom artifact inside a lane only to clarify who performs the lane activities; it does not replace the lane.

### AI Guidance

Do not infer additional participants, lanes, control flow, or responsibility semantics from Actor artifacts.

### Diagnostic Messages

- `default`: Actor artifacts are documentation only and require modelling context

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## lane-lane-labels-business-roles-performers

- **Name**: Lane Labels Business Roles Performers
- **Category**: Lane
- **Severity**: WARNING
- **Target Elements**: `bpmn:Lane`

### Purpose

Require lane labels that identify the responsible business role or performer.

### Modeller Guidance

Name each lane by the business role or performer responsible for the activities in that lane.

### AI Guidance

Deterministically require lane labels to be present; judging whether the label is the correct role requires business context.

### Diagnostic Messages

- `default`: Lane must have a business role or performer name

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## msg-message-flow-across-pools

- **Name**: Message Flow Across Pools
- **Category**: Message
- **Severity**: ERROR
- **Target Elements**: `bpmn:MessageFlow`

### Purpose

Ensure message flow models inter-participant communication.

### Modeller Guidance

Use message flows only between different pools or participants, not within a single pool.

### AI Guidance

Detect message flows whose source and target resolve to the same pool or cannot be mapped to valid pools.

### Diagnostic Messages

- `default`: Message flow must connect elements in different pools

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## msg-message-flow-name-pattern

- **Name**: Message Flow Name Pattern
- **Category**: Message
- **Severity**: WARNING
- **Target Elements**: `bpmn:MessageFlow`

### Purpose

Encourage noun-based message naming over action phrasing.

### Modeller Guidance

Label message flows with the message name, such as Approval confirmation, rather than an action such as Send approval.

### AI Guidance

Detect message flow labels that start with a verb or auxiliary token.

### Diagnostic Messages

- `default`: Message flow name should describe the message, not an action

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## name-no-element-type-words

- **Name**: No Element Type Words
- **Category**: Name
- **Severity**: ERROR
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`, `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`, `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`, `bpmn:ComplexGateway`, `bpmn:DataObjectReference`, `bpmn:DataStoreReference`

### Purpose

Avoid redundant BPMN element type words in labels.

### Modeller Guidance

Do not include words such as activity, process, or event in element names because the BPMN shape already indicates the type.

### AI Guidance

Detect named BPMN elements whose labels include redundant element type words.

### Diagnostic Messages

- `default`: Element name must not include its BPMN element type

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `stripTypeWords`

## name-uncommon-abbreviations

- **Name**: Uncommon Abbreviations
- **Category**: Name
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`, `bpmn:StartEvent`, `bpmn:IntermediateCatchEvent`, `bpmn:IntermediateThrowEvent`, `bpmn:EndEvent`, `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`, `bpmn:ComplexGateway`, `bpmn:DataObjectReference`, `bpmn:DataStoreReference`

### Purpose

Reduce ambiguity from obscure abbreviations in BPMN labels.

### Modeller Guidance

Avoid uncommon abbreviations in labels, or explain them with an annotation or glossary.

### AI Guidance

Detect uppercase abbreviations that are not on the common acronym allow-list and ask for a clearer label or explanation.

### Diagnostic Messages

- `default`: Avoid uncommon abbreviations in labels or explain them via annotation/glossary

### Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `expandAbbreviations`

### Replacements
- `AUTH` → `authentication`
- `CFG` → `configuration`
- `DOC` → `document`
- `ITBL` → `itinerary block`
- `MSG` → `message`
- `REQ` → `request`
- `RESP` → `response`

## pool-black-box-pool-named-by-external-entity-or-process

- **Name**: Black Box Pool Named By External Entity Or Process
- **Category**: Pool
- **Severity**: WARNING
- **Target Elements**: `bpmn:Participant`

### Purpose

Ensure black-box pools are identifiable external participants.

### Modeller Guidance

Name black-box pools using the external entity, organization, department, system, or external process they represent.

### AI Guidance

For pools without a process reference, deterministically require a non-empty label; semantic entity checks require modelling context.

### Diagnostic Messages

- `default`: Black-box pool must have a name

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## pool-child-diagrams-keep-pool-process-name

- **Name**: Child Diagrams Keep Pool Process Name
- **Category**: Pool
- **Severity**: WARNING
- **Target Elements**: `bpmn:Participant`

### Purpose

Keep child-level pool labels aligned with the upper-level process name.

### Modeller Guidance

When a child diagram elaborates a subprocess, keep the pool label as the upper-level process name rather than renaming it to the subprocess.

### AI Guidance

Compare parent and child diagram context when available; a single BPMN XML document does not reliably prove cross-level naming intent.

### Diagnostic Messages

- `default`: Child diagram pool should keep the parent process name

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`

## pool-white-box-pool-named-by-process

- **Name**: White Box Pool Named By Process
- **Category**: Pool
- **Severity**: WARNING
- **Target Elements**: `bpmn:Participant`

### Purpose

Name white-box pools after the process they expose.

### Modeller Guidance

Use the process name as the label of a white-box pool, not an organization, department, or role.

### AI Guidance

For pools with a process reference, compare the participant label with the referenced process label when both are present.

### Diagnostic Messages

- `default`: White-box pool name should match the referenced process name

### Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
