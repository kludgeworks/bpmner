package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnRenderer
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
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

@SecondaryAdapter
@Component
internal open class BpmnDefinitionToXmlConverter : BpmnRenderer {
    companion object {
        private const val TARGET_NAMESPACE = "https://groknull.dev/bpmner"
        private const val EXPORTER = "bpmner"
        private const val EXPORTER_VERSION = "0.0.1"
    }

    fun toXml(definition: BpmnDefinition): String = render(definition).xml

    override fun render(graph: LaidOutProcessGraph): RenderedBpmn {
        val rendered = render(graph.definition)
        return rendered.copy(sourceGraph = graph)
    }

    override fun render(definition: BpmnDefinition): RenderedBpmn {
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
        val modelInstance =
            Bpmn
                .createExecutableProcess(definition.processId)
                .name(definition.processName)
                .done()

        val process =
            modelInstance
                .getModelElementsByType(Process::class.java)
                .firstOrNull()
                ?: error("Unable to locate process in Camunda model instance")

        val definitions =
            modelInstance.definitions
                ?: error("Unable to locate definitions in Camunda model instance")
        configureDefinitions(definitions)

        val plane = BpmnModelFactory.createDiagramPlane(modelInstance, definitions, process)
        val (nodeMap, shapeMap) = buildNodeMaps(modelInstance, definition, process, plane)
        buildSequenceFlows(modelInstance, definition, process, plane, nodeMap, shapeMap)
        return modelInstance
    }

    private fun buildNodeMaps(
        modelInstance: BpmnModelInstance,
        definition: BpmnDefinition,
        process: Process,
        plane: BpmnPlane,
    ): Pair<Map<String, FlowNode>, Map<String, BpmnShape>> {
        val nodeMap = mutableMapOf<String, FlowNode>()
        val shapeMap = mutableMapOf<String, BpmnShape>()
        for (node in definition.nodes) {
            val flowNode = BpmnModelFactory.newFlowNode(modelInstance, node)
            process.addChildElement(flowNode)
            nodeMap[node.id] = flowNode
            val shape = modelInstance.newInstance(BpmnShape::class.java)
            shape.id = "${node.id}_di"
            shape.bpmnElement = flowNode
            shape.bounds = BpmnModelFactory.newBounds(modelInstance, node.bounds)
            plane.addChildElement(shape)
            shapeMap[node.id] = shape
        }
        return nodeMap to shapeMap
    }

    @Suppress("LongParameterList") // all params structurally required; no meaningful grouping
    private fun buildSequenceFlows(
        modelInstance: BpmnModelInstance,
        definition: BpmnDefinition,
        process: Process,
        plane: BpmnPlane,
        nodeMap: Map<String, FlowNode>,
        shapeMap: Map<String, BpmnShape>,
    ) {
        for (edge in definition.sequences) {
            val source =
                nodeMap[edge.sourceRef]
                    ?: throw IllegalArgumentException("Unknown sourceRef '${edge.sourceRef}' on edge '${edge.id}'")
            val target =
                nodeMap[edge.targetRef]
                    ?: throw IllegalArgumentException("Unknown targetRef '${edge.targetRef}' on edge '${edge.id}'")
            val sequenceFlow = modelInstance.newInstance(SequenceFlow::class.java)
            sequenceFlow.id = edge.id
            if (!edge.name.isNullOrBlank()) sequenceFlow.name = edge.name
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
            val diEdge =
                modelInstance.newInstance(org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge::class.java)
            diEdge.id = "${edge.id}_di"
            diEdge.bpmnElement = sequenceFlow
            diEdge.sourceElement = shapeMap[edge.sourceRef]
            diEdge.targetElement = shapeMap[edge.targetRef]
            edge.waypoints.forEach { waypoint ->
                diEdge.addChildElement(BpmnModelFactory.newWaypoint(modelInstance, waypoint))
            }
            plane.addChildElement(diEdge)
        }
    }

    private fun configureDefinitions(definitions: Definitions) {
        definitions.targetNamespace = TARGET_NAMESPACE
        definitions.exporter = EXPORTER
        definitions.exporterVersion = EXPORTER_VERSION
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
