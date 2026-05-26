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
    const val PARALLEL_GATEWAY = "bpmn:ParallelGateway"
}
