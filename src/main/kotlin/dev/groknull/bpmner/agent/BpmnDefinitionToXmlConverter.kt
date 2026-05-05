package dev.groknull.bpmner.agent

import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.UserTask
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

@Component
class BpmnDefinitionToXmlConverter {

    fun toXml(definition: BpmnDefinition): String {
        val modelInstance = toModelInstance(definition)
        val output = ByteArrayOutputStream()
        Bpmn.writeModelToStream(output, modelInstance)
        return output.toString(Charsets.UTF_8)
    }

    private fun toModelInstance(definition: BpmnDefinition): BpmnModelInstance {
        val modelInstance = Bpmn.createExecutableProcess(definition.processId)
            .name(definition.processName)
            .done()

        val process = modelInstance
            .getModelElementsByType(Process::class.java)
            .firstOrNull()
            ?: error("Unable to locate process in Camunda model instance")

        val nodeMap = mutableMapOf<String, FlowNode>()

        for (node in definition.nodes) {
            val flowNode = newFlowNode(modelInstance, node)
            process.addChildElement(flowNode)
            nodeMap[node.id] = flowNode
        }

        for (edge in definition.sequences) {
            val source = nodeMap[edge.sourceRef]
                ?: throw IllegalArgumentException("Unknown sourceRef '${edge.sourceRef}' on edge '${edge.id}'")
            val target = nodeMap[edge.targetRef]
                ?: throw IllegalArgumentException("Unknown targetRef '${edge.targetRef}' on edge '${edge.id}'")

            val sequenceFlow = modelInstance.newInstance(SequenceFlow::class.java)
            sequenceFlow.id = edge.id
            if (!edge.name.isNullOrBlank()) {
                sequenceFlow.name = edge.name
            }
            sequenceFlow.source = source
            sequenceFlow.target = target
            process.addChildElement(sequenceFlow)

            source.outgoing.add(sequenceFlow)
            target.incoming.add(sequenceFlow)
        }

        return modelInstance
    }

    private fun newFlowNode(modelInstance: BpmnModelInstance, node: BpmnNode): FlowNode {
        val flowNode = when (node.type) {
            NodeType.START_EVENT -> modelInstance.newInstance(StartEvent::class.java)
            NodeType.USER_TASK -> modelInstance.newInstance(UserTask::class.java)
            NodeType.SERVICE_TASK -> modelInstance.newInstance(ServiceTask::class.java)
            NodeType.EXCLUSIVE_GATEWAY -> modelInstance.newInstance(ExclusiveGateway::class.java)
            NodeType.END_EVENT -> modelInstance.newInstance(EndEvent::class.java)
        }

        flowNode.id = node.id
        flowNode.name = node.name
        return flowNode
    }
}
