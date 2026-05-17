package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.Definitions
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.UserTask

internal object BpmnModelFactory {
    fun newFlowNode(
        modelInstance: BpmnModelInstance,
        node: BpmnNode,
    ): FlowNode {
        val flowNode =
            when (node.type) {
                NodeType.START_EVENT -> modelInstance.newInstance(StartEvent::class.java)
                NodeType.USER_TASK -> modelInstance.newInstance(UserTask::class.java)
                NodeType.SERVICE_TASK -> modelInstance.newInstance(ServiceTask::class.java)
                NodeType.EXCLUSIVE_GATEWAY -> modelInstance.newInstance(ExclusiveGateway::class.java)
                NodeType.END_EVENT -> modelInstance.newInstance(EndEvent::class.java)
            }
        flowNode.id = node.id
        BpmnNodeNamingPolicy.normalize(node.name)?.let { flowNode.name = it }
        return flowNode
    }
}
