/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

internal object BpmnTypeName {
    const val DEFINITIONS = "bpmn:Definitions"
    const val PROCESS = "bpmn:Process"
    const val FLOW_ELEMENT = "bpmn:FlowElement"
    const val FLOW_NODE = "bpmn:FlowNode"
    const val TASK = "bpmn:Task"
    const val GATEWAY = "bpmn:Gateway"
    const val EVENT = "bpmn:Event"
    const val SEQUENCE_FLOW = "bpmn:SequenceFlow"
    const val START_EVENT = "bpmn:StartEvent"
    const val END_EVENT = "bpmn:EndEvent"
    const val INTERMEDIATE_CATCH_EVENT = "bpmn:IntermediateCatchEvent"
    const val INTERMEDIATE_THROW_EVENT = "bpmn:IntermediateThrowEvent"
    const val BOUNDARY_EVENT = "bpmn:BoundaryEvent"
    const val USER_TASK = "bpmn:UserTask"
    const val SERVICE_TASK = "bpmn:ServiceTask"
    const val SCRIPT_TASK = "bpmn:ScriptTask"
    const val BUSINESS_RULE_TASK = "bpmn:BusinessRuleTask"
    const val SEND_TASK = "bpmn:SendTask"
    const val RECEIVE_TASK = "bpmn:ReceiveTask"
    const val MANUAL_TASK = "bpmn:ManualTask"
    const val EXCLUSIVE_GATEWAY = "bpmn:ExclusiveGateway"
    const val INCLUSIVE_GATEWAY = "bpmn:InclusiveGateway"
    const val PARALLEL_GATEWAY = "bpmn:ParallelGateway"
    const val EVENT_BASED_GATEWAY = "bpmn:EventBasedGateway"

    // An embedded subprocess is an Activity (a FlowNode), not a task/gateway/event. Surfaced so
    // FlowNode/FlowElement-targeting rules see it; not a member of TASK/GATEWAY/EVENT categories.
    const val SUB_PROCESS = "bpmn:SubProcess"

    // A call activity is an Activity (a FlowNode) that delegates to another process. Surfaced so
    // FlowNode/FlowElement-targeting rules (and TaskVsSubprocessVsCallActivity) see it; like
    // SUB_PROCESS it is not a member of the TASK/GATEWAY/EVENT categories.
    const val CALL_ACTIVITY = "bpmn:CallActivity"

    // Artifact, not a flow node: surfaced so annotation-targeting rules (TextAnnotationUsage,
    // and the association sub-checks of MiTaskAnnotation) can match it. Deliberately absent from
    // `broadTypeMembers`/`supportedTypeNames` in BpmnTypeMatcher — it is not a FlowNode/FlowElement
    // and matches only by exact type name.
    const val TEXT_ANNOTATION = "bpmn:TextAnnotation"

    // Data elements, not flow nodes: surfaced so the data-naming rule (NoTypeWordsInDataName) can
    // match them by exact type name. Like TEXT_ANNOTATION, deliberately absent from
    // `broadTypeMembers`/`supportedTypeNames` in BpmnTypeMatcher — they are not FlowNode/FlowElement.
    const val DATA_OBJECT = "bpmn:DataObject"
    const val DATA_STORE = "bpmn:DataStore"

    // Artifact, not a flow node: surfaced so GroupUsage can teach that groups are visual
    // containers only. Deliberately absent from broad type matching and supported node names.
    const val GROUP = "bpmn:Group"

    // Collaboration constructs: participants (pools) and lanes are surfaced so the pool/lane
    // naming and usage rules can target them by exact type name; message flows are surfaced both as
    // elements (for name-pattern rules) and as `PrimitiveModelContext.messageFlows` (for the
    // across-pool connectivity check). Not flow nodes — absent from broad type matching.
    const val PARTICIPANT = "bpmn:Participant"
    const val LANE = "bpmn:Lane"
    const val MESSAGE_FLOW = "bpmn:MessageFlow"

    // BPMN DI diagram typename. The parser surfaces its document-level count on
    // `BpmnDefinition.diagramCount`; `PrimitiveModelMapping` projects that count into the
    // primitive model as N synthetic `PrimitiveElement` entries so `CardinalityCheck` can
    // count them.
    //
    // Not added to `BpmnTypeMatcher.supportedTypeNames` — that set is "types the domain
    // model represents as typed `BpmnNode` subtypes". DI is metadata, not a node. The
    // `CardinalityCheck` guard skips `isSupportedProductionType` for `max`-only rules,
    // which is the path the `NoDuplicateDiagrams` rule takes on this typename.
    const val DIAGRAM = "bpmndi:BPMNDiagram"
}
