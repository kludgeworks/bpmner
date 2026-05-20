/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent
import org.camunda.bpm.model.bpmn.instance.ParallelGateway
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.UserTask

internal object BpmnModelFactory {
    fun newFlowNode(
        modelInstance: BpmnModelInstance,
        node: BpmnNode,
    ): FlowNode {
        val flowNode =
            when (node) {
                is BpmnStartEvent -> modelInstance.newInstance(StartEvent::class.java)
                is BpmnUserTask -> modelInstance.newInstance(UserTask::class.java)
                is BpmnServiceTask -> modelInstance.newInstance(ServiceTask::class.java)
                is BpmnExclusiveGateway -> modelInstance.newInstance(ExclusiveGateway::class.java)
                is BpmnParallelGateway -> modelInstance.newInstance(ParallelGateway::class.java)
                is BpmnIntermediateCatchEvent -> modelInstance.newInstance(IntermediateCatchEvent::class.java)
                is BpmnIntermediateThrowEvent -> modelInstance.newInstance(IntermediateThrowEvent::class.java)
                is BpmnBoundaryEvent -> modelInstance.newInstance(BoundaryEvent::class.java)
                is BpmnEndEvent -> modelInstance.newInstance(EndEvent::class.java)
            }
        flowNode.id = node.id
        BpmnNodeNamingPolicy.normalize(node.name)?.let { flowNode.name = it }
        return flowNode
    }
}
