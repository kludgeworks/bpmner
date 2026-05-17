package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnRenderer
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.ConditionExpression
import org.camunda.bpm.model.bpmn.instance.Definitions
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape
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
        val nodeMap = buildNodeMaps(modelInstance, definition, process)
        buildSequenceFlows(
            ConversionContext(
                modelInstance = modelInstance,
                definition = definition,
                process = process,
                nodeMap = nodeMap,
            ),
        )
        return modelInstance
    }

    private fun buildNodeMaps(modelInstance: BpmnModelInstance, definition: BpmnDefinition, process: org.camunda.bpm.model.bpmn.instance.Process): Map<String, FlowNode> {
        val nodeMap = mutableMapOf<String, FlowNode>()
                for (node in definition.nodes) {
            val flowNode = BpmnModelFactory.newFlowNode(modelInstance, node)
            process.addChildElement(flowNode)
            nodeMap[node.id] = flowNode

                    }
        return nodeMap
    }

    private data class ConversionContext(
        val modelInstance: BpmnModelInstance,
        val definition: BpmnDefinition,
        val process: Process,
        val nodeMap: Map<String, FlowNode>
    )

    private fun buildSequenceFlows(context: ConversionContext) {
        for (edge in context.definition.sequences) {
            val source =
                context.nodeMap[edge.sourceRef]
                    ?: throw IllegalArgumentException("Unknown sourceRef '${edge.sourceRef}' on edge '${edge.id}'")
            val target =
                context.nodeMap[edge.targetRef]
                    ?: throw IllegalArgumentException("Unknown targetRef '${edge.targetRef}' on edge '${edge.id}'")
            val sequenceFlow = context.modelInstance.newInstance(SequenceFlow::class.java)
            sequenceFlow.id = edge.id
            if (!edge.name.isNullOrBlank()) sequenceFlow.name = edge.name
            if (!edge.conditionExpression.isNullOrBlank()) {
                val conditionExpression = context.modelInstance.newInstance(ConditionExpression::class.java)
                conditionExpression.textContent = edge.conditionExpression
                sequenceFlow.conditionExpression = conditionExpression
            }
            sequenceFlow.source = source
            sequenceFlow.target = target
            context.process.addChildElement(sequenceFlow)
            source.outgoing.add(sequenceFlow)
            target.incoming.add(sequenceFlow)
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
