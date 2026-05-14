package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.generation.BpmnXmlParser
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
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge as DiBpmnEdge

@SecondaryAdapter
@Component
internal open class BpmnXmlToDefinitionConverter : BpmnXmlParser {
    override fun parse(xml: String): BpmnDefinition {
        val model: BpmnModelInstance = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val process =
            model.getModelElementsByType(Process::class.java).firstOrNull()
                ?: error("BPMN XML contains no <process> element")

        val shapesByElementId =
            model
                .getModelElementsByType(BpmnShape::class.java)
                .filter { it.bpmnElement != null }
                .associateBy { it.bpmnElement.id }

        val edgesByElementId =
            model
                .getModelElementsByType(DiBpmnEdge::class.java)
                .filter { it.bpmnElement != null }
                .associateBy { it.bpmnElement.id }

        val nodes =
            model.getModelElementsByType(FlowNode::class.java).map { flowNode ->
                BpmnNode(
                    id = flowNode.id,
                    name = flowNode.name,
                    type = flowNode.toNodeType(),
                    bounds = shapesByElementId[flowNode.id].toBounds(flowNode.id),
                )
            }

        val sequences =
            model.getModelElementsByType(SequenceFlow::class.java).map { flow ->
                BpmnEdge(
                    id = flow.id,
                    sourceRef = flow.source.id,
                    targetRef = flow.target.id,
                    name = flow.name?.takeIf { it.isNotBlank() },
                    conditionExpression = flow.conditionExpression?.textContent?.takeIf { it.isNotBlank() },
                    waypoints = edgesByElementId[flow.id].toWaypoints(flow.id),
                )
            }

        return BpmnDefinition(
            processId = process.id,
            processName = process.name?.takeIf { it.isNotBlank() } ?: process.id,
            nodes = nodes,
            sequences = sequences,
        )
    }

    private fun FlowNode.toNodeType(): NodeType =
        when (this) {
            is StartEvent -> NodeType.START_EVENT
            is UserTask -> NodeType.USER_TASK
            is ServiceTask -> NodeType.SERVICE_TASK
            is ExclusiveGateway -> NodeType.EXCLUSIVE_GATEWAY
            is EndEvent -> NodeType.END_EVENT
            else -> error("Unsupported BPMN flow node type for id='$id': ${this::class.simpleName}")
        }

    private fun BpmnShape?.toBounds(elementId: String): BpmnBounds {
        val shape = this ?: error("Missing BPMNDI shape for element id='$elementId'")
        val bounds = shape.bounds ?: error("BPMNDI shape for element id='$elementId' has no bounds")
        return BpmnBounds(x = bounds.x, y = bounds.y, width = bounds.width, height = bounds.height)
    }

    private fun DiBpmnEdge?.toWaypoints(elementId: String): List<BpmnWaypoint> {
        val edge = this ?: error("Missing BPMNDI edge for element id='$elementId'")
        return edge.waypoints.map { BpmnWaypoint(x = it.x, y = it.y) }
    }
}
