/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.api.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnCallActivity
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnEventBasedGateway
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnInclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnScriptTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnSubProcess
import dev.groknull.bpmner.core.BpmnUnrecognizedNode
import dev.groknull.bpmner.core.BpmnUserTask
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask
import org.camunda.bpm.model.bpmn.instance.CallActivity
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.EventBasedGateway
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent
import org.camunda.bpm.model.bpmn.instance.ManualTask
import org.camunda.bpm.model.bpmn.instance.ParallelGateway
import org.camunda.bpm.model.bpmn.instance.ReceiveTask
import org.camunda.bpm.model.bpmn.instance.ScriptTask
import org.camunda.bpm.model.bpmn.instance.SendTask
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.UserTask

internal object BpmnModelFactory {
    @Suppress("CyclomaticComplexMethod") // one arm per sealed subtype — the count IS the safety property
    fun newFlowNode(
        modelInstance: BpmnModelInstance,
        node: BpmnNode,
    ): FlowNode {
        val flowNode =
            when (node) {
                is BpmnStartEvent -> modelInstance.newInstance(StartEvent::class.java)

                is BpmnUserTask -> modelInstance.newInstance(UserTask::class.java)

                is BpmnServiceTask -> modelInstance.newInstance(ServiceTask::class.java)

                is BpmnScriptTask -> modelInstance.newInstance(ScriptTask::class.java)

                is BpmnBusinessRuleTask -> modelInstance.newInstance(BusinessRuleTask::class.java)

                is BpmnSendTask -> modelInstance.newInstance(SendTask::class.java)

                is BpmnReceiveTask -> modelInstance.newInstance(ReceiveTask::class.java)

                is BpmnManualTask -> modelInstance.newInstance(ManualTask::class.java)

                is BpmnExclusiveGateway -> modelInstance.newInstance(ExclusiveGateway::class.java)

                is BpmnInclusiveGateway -> modelInstance.newInstance(InclusiveGateway::class.java)

                is BpmnParallelGateway -> modelInstance.newInstance(ParallelGateway::class.java)

                is BpmnEventBasedGateway -> modelInstance.newInstance(EventBasedGateway::class.java)

                is BpmnIntermediateCatchEvent -> modelInstance.newInstance(IntermediateCatchEvent::class.java)

                is BpmnIntermediateThrowEvent -> modelInstance.newInstance(IntermediateThrowEvent::class.java)

                is BpmnBoundaryEvent -> modelInstance.newInstance(BoundaryEvent::class.java)

                is BpmnEndEvent -> modelInstance.newInstance(EndEvent::class.java)

                is BpmnSubProcess ->
                    modelInstance.newInstance(SubProcess::class.java).apply {
                        setTriggeredByEvent(node.triggeredByEvent)
                    }

                is BpmnCallActivity ->
                    modelInstance.newInstance(CallActivity::class.java).apply {
                        calledElement = node.calledElement
                    }

                // Unrecognized nodes carry no Camunda model class. Callers are expected to
                // filter them out before reaching the generator; reaching here is a contract
                // violation.
                is BpmnUnrecognizedNode -> error(
                    "BpmnUnrecognizedNode '${node.id}' (${node.bpmnType}) cannot be converted to a Camunda FlowNode. " +
                        "The generator pipeline must filter unrecognized nodes before reaching BpmnModelFactory.",
                )
            }
        flowNode.id = node.id
        BpmnNodeNamingPolicy.normalize(node.name)?.let { flowNode.name = it }
        return flowNode
    }
}
