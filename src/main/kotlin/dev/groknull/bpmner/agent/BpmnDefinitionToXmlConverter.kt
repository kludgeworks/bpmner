package dev.groknull.bpmner.agent

import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.ConditionExpression
import org.camunda.bpm.model.bpmn.instance.Definitions
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.UserTask
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape
import org.camunda.bpm.model.bpmn.instance.dc.Bounds
import org.camunda.bpm.model.bpmn.instance.di.Waypoint
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

@Component
class BpmnDefinitionToXmlConverter {

    companion object {
        private const val TARGET_NAMESPACE = "https://groknull.dev/bpmner"
        private const val EXPORTER = "bpmner"
        private const val EXPORTER_VERSION = "0.0.1"
    }

    fun toXml(definition: BpmnDefinition): String {
        return render(definition).xml
    }

    fun render(graph: LaidOutProcessGraph): RenderedBpmn {
        val rendered = render(graph.definition)
        return rendered.copy(sourceGraph = graph)
    }

    fun render(definition: BpmnDefinition): RenderedBpmn {
        val modelInstance = toModelInstance(definition)
        val output = ByteArrayOutputStream()
        Bpmn.writeModelToStream(output, modelInstance)
        return RenderedBpmn(
            definition = definition,
            xml = output.toString(Charsets.UTF_8),
            elementIndex = buildElementIndex(definition),
        )
    }

    private fun toModelInstance(definition: BpmnDefinition): BpmnModelInstance {
        val modelInstance = Bpmn.createExecutableProcess(definition.processId)
            .name(definition.processName)
            .done()

        val process = modelInstance
            .getModelElementsByType(Process::class.java)
            .firstOrNull()
            ?: error("Unable to locate process in Camunda model instance")

        val definitions = modelInstance.definitions
            ?: error("Unable to locate definitions in Camunda model instance")
        configureDefinitions(definitions)

        val nodeMap = mutableMapOf<String, FlowNode>()
        val shapeMap = mutableMapOf<String, BpmnShape>()
        val plane = createDiagramPlane(modelInstance, definitions, process)

        for (node in definition.nodes) {
            val flowNode = newFlowNode(modelInstance, node)
            process.addChildElement(flowNode)
            nodeMap[node.id] = flowNode

            val shape = modelInstance.newInstance(BpmnShape::class.java)
            shape.id = "${node.id}_di"
            shape.bpmnElement = flowNode
            shape.bounds = newBounds(modelInstance, node.bounds)
            plane.addChildElement(shape)
            shapeMap[node.id] = shape
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
            if (!edge.conditionExpression.isNullOrBlank()) {
                val conditionExpression = modelInstance.newInstance(ConditionExpression::class.java)
                conditionExpression.textContent = edge.conditionExpression
                sequenceFlow.conditionExpression = conditionExpression
            }
            sequenceFlow.source = source
            sequenceFlow.target = target
            process.addChildElement(sequenceFlow)

            source.outgoing.add(sequenceFlow)
            target.incoming.add(sequenceFlow)

            val diEdge = modelInstance.newInstance(org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge::class.java)
            diEdge.id = "${edge.id}_di"
            diEdge.bpmnElement = sequenceFlow
            diEdge.sourceElement = shapeMap[edge.sourceRef]
            diEdge.targetElement = shapeMap[edge.targetRef]
            edge.waypoints.forEach { waypoint ->
                diEdge.addChildElement(newWaypoint(modelInstance, waypoint))
            }
            plane.addChildElement(diEdge)
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

    private fun configureDefinitions(definitions: Definitions) {
        definitions.targetNamespace = TARGET_NAMESPACE
        definitions.exporter = EXPORTER
        definitions.exporterVersion = EXPORTER_VERSION
    }

    private fun createDiagramPlane(
        modelInstance: BpmnModelInstance,
        definitions: Definitions,
        process: Process,
    ): BpmnPlane {
        val existingDiagram = modelInstance.getModelElementsByType(BpmnDiagram::class.java).firstOrNull()

        val diagram = existingDiagram ?: modelInstance.newInstance(BpmnDiagram::class.java).also {
            definitions.addChildElement(it)
        }
        diagram.id = "${process.id}_diagram"

        val existingPlane = diagram.bpmnPlane
        val plane = existingPlane ?: modelInstance.newInstance(BpmnPlane::class.java).also {
            diagram.bpmnPlane = it
        }
        plane.id = "${process.id}_plane"
        plane.bpmnElement = process

        return plane
    }

    private fun newBounds(modelInstance: BpmnModelInstance, bounds: BpmnBounds): Bounds {
        val diBounds = modelInstance.newInstance(Bounds::class.java)
        diBounds.x = bounds.x
        diBounds.y = bounds.y
        diBounds.width = bounds.width
        diBounds.height = bounds.height
        return diBounds
    }

    private fun newWaypoint(modelInstance: BpmnModelInstance, waypoint: BpmnWaypoint): Waypoint {
        val diWaypoint = modelInstance.newInstance(Waypoint::class.java)
        diWaypoint.x = waypoint.x
        diWaypoint.y = waypoint.y
        return diWaypoint
    }

    private fun buildElementIndex(definition: BpmnDefinition): BpmnElementIndex =
        BpmnElementIndex(
            processId = definition.processId,
            nodeObjectRefs = definition.nodes.associate { it.id to "nodes[id=${it.id}]" },
            edgeObjectRefs = definition.sequences.associate { it.id to "sequences[id=${it.id}]" },
            shapeIdsByNodeId = definition.nodes.associate { it.id to "${it.id}_di" },
            edgeDiagramIdsByEdgeId = definition.sequences.associate { it.id to "${it.id}_di" },
        )
}
