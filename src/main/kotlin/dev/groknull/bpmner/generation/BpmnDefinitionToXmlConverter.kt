/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnTask
import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnElementIndex
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnRenderer
import dev.groknull.bpmner.generation.internal.adapter.outbound.BpmnModelFactory
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.ConditionExpression
import org.camunda.bpm.model.bpmn.instance.Definitions
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

@SecondaryAdapter
@Component
@Suppress("TooManyFunctions")
internal open class BpmnDefinitionToXmlConverter : BpmnRenderer {
    companion object {
        private const val TARGET_NAMESPACE = "https://groknull.dev/bpmner"
        private const val BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"

        // Foreign-namespace extension prefix for attributes we carry on flow elements that the
        // BPMN 2.0 spec doesn't define (e.g. `decisionRef` on businessRuleTask). The BPMN XSD
        // allows `xs:anyAttribute namespace="##other"` on most complex types, so qualifying
        // these in our own namespace keeps XSD validation happy while still round-tripping.
        const val BPMNER_EXT_NS = "https://groknull.dev/bpmner/ext"
        private const val EXPORTER = "bpmner"
        private const val EXPORTER_VERSION = "0.0.1"
        private const val DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl"
        private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
        private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
        private val UNUSED_DI_NAMESPACES_REGEX = Regex("\\s+xmlns:(?:bpmndi|omgdi|di|dc)=\"[^\"]*\"")

        // All seven task element local names — any task kind may carry a multi-instance marker.
        private val ALL_TASK_LOCAL_NAMES = listOf(
            "userTask",
            "serviceTask",
            "scriptTask",
            "manualTask",
            "businessRuleTask",
            "sendTask",
            "receiveTask",
        )
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
        val xml = addCatalogsAndEventDefinitions(output.toString(Charsets.UTF_8), definition)
        return RenderedBpmn(
            definition = definition,
            xml = stripUnusedDiNamespaces(xml),
            elementIndex = buildElementIndex(definition),
        )
    }

    // Camunda's writer auto-emits `xmlns:bpmndi` even when no diagram interchange elements are
    // present. We render semantic XML only; the downstream auto-layout stage adds BPMNDI. Strip
    // the unused namespace declaration so the output is clean and our own strict parser accepts
    // a round-trip.
    private fun stripUnusedDiNamespaces(xml: String): String = xml.replace(UNUSED_DI_NAMESPACES_REGEX, "")

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

        val nodeMap = buildNodeMap(modelInstance, definition, process)
        buildSequenceFlows(modelInstance, definition, process, nodeMap)
        removeAutoGeneratedDiagramInterchange(modelInstance, definitions)
        return modelInstance
    }

    // `Bpmn.createExecutableProcess(...)` auto-creates an empty <bpmndi:BPMNDiagram> skeleton.
    // We don't want any BPMNDI in semantic-only output, so drop it before serialization.
    private fun removeAutoGeneratedDiagramInterchange(
        modelInstance: BpmnModelInstance,
        definitions: Definitions,
    ) {
        modelInstance
            .getModelElementsByType(BpmnDiagram::class.java)
            .toList()
            .forEach { definitions.removeChildElement(it) }
    }

    private fun buildNodeMap(
        modelInstance: BpmnModelInstance,
        definition: BpmnDefinition,
        process: Process,
    ): Map<String, FlowNode> {
        val nodeMap = mutableMapOf<String, FlowNode>()
        for (node in definition.nodes) {
            val flowNode = BpmnModelFactory.newFlowNode(modelInstance, node)
            process.addChildElement(flowNode)
            nodeMap[node.id] = flowNode
        }
        return nodeMap
    }

    private fun buildSequenceFlows(
        modelInstance: BpmnModelInstance,
        definition: BpmnDefinition,
        process: Process,
        nodeMap: Map<String, FlowNode>,
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
            if (edge.isDefault) {
                when (source) {
                    is ExclusiveGateway -> source.default = sequenceFlow

                    is InclusiveGateway -> source.default = sequenceFlow

                    else -> error(
                        "edge ${edge.id}: isDefault is only supported on exclusive- or inclusive-gateway " +
                            "sources, got ${source::class.simpleName}",
                    )
                }
            }
        }
    }

    private fun configureDefinitions(definitions: Definitions) {
        definitions.targetNamespace = TARGET_NAMESPACE
        definitions.exporter = EXPORTER
        definitions.exporterVersion = EXPORTER_VERSION
    }

    private fun buildElementIndex(definition: BpmnDefinition): BpmnElementIndex = BpmnElementIndex(
        processId = definition.processId,
        nodeObjectRefs = definition.nodes.associate { it.id to "nodes[id=${it.id}]" },
        edgeObjectRefs = definition.sequences.associate { it.id to "sequences[id=${it.id}]" },
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun addCatalogsAndEventDefinitions(
        xml: String,
        definition: BpmnDefinition,
    ): String {
        val document = parseDocument(xml)
        val root = document.documentElement
        val process = root.getElementsByTagNameNS(BPMN_NS, "process").item(0) as Element
        val eventElementsById = document.eventElementsById()

        definition.escalations.asReversed().forEach { escalation ->
            root.insertBefore(
                document.bpmnElement("escalation").also {
                    it.setAttribute("id", escalation.id)
                    it.setAttribute("escalationCode", escalation.code)
                    escalation.name?.takeIf { name -> name.isNotBlank() }?.let { name -> it.setAttribute("name", name) }
                },
                process,
            )
        }
        definition.errors.asReversed().forEach { error ->
            root.insertBefore(
                document.bpmnElement("error").also {
                    it.setAttribute("id", error.id)
                    it.setAttribute("errorCode", error.code)
                    error.name?.takeIf { name -> name.isNotBlank() }?.let { name -> it.setAttribute("name", name) }
                },
                process,
            )
        }
        definition.signals.asReversed().forEach { signal ->
            root.insertBefore(
                document.bpmnElement("signal").also {
                    it.setAttribute("id", signal.id)
                    it.setAttribute("name", signal.name)
                },
                process,
            )
        }
        definition.messages.asReversed().forEach { message ->
            root.insertBefore(
                document.bpmnElement("message").also {
                    it.setAttribute("id", message.id)
                    it.setAttribute("name", message.name)
                },
                process,
            )
        }

        val taskElementsById = document.taskElementsById()
        var bpmnerNamespaceUsed = false

        definition.nodes.forEach { node ->
            when (node) {
                is BpmnStartEvent -> {
                    val element = eventElementsById.eventElement(node.id)
                    if (!node.isInterrupting) {
                        element.setAttribute("isInterrupting", node.isInterrupting.toString())
                    }
                    element.appendEventDefinition(document, node.eventDefinition)
                }

                is BpmnIntermediateCatchEvent -> {
                    eventElementsById.eventElement(node.id).appendEventDefinition(document, node.eventDefinition)
                }

                is BpmnIntermediateThrowEvent -> {
                    eventElementsById.eventElement(node.id).appendEventDefinition(document, node.eventDefinition)
                }

                is BpmnBoundaryEvent -> {
                    val element = eventElementsById.eventElement(node.id)
                    element.setAttribute("attachedToRef", node.attachedToRef)
                    element.setAttribute("cancelActivity", node.cancelActivity.toString())
                    element.appendEventDefinition(document, node.eventDefinition)
                }

                is BpmnEndEvent -> {
                    eventElementsById.eventElement(node.id).appendEventDefinition(document, node.eventDefinition)
                }

                // messageRef on send / receive tasks is a BPMN-spec attribute on the task
                // element itself (no event-definition wrapper), so stamp it directly.
                is BpmnSendTask -> {
                    taskElementsById.taskElement(node.id).setAttribute("messageRef", node.messageRef)
                }

                is BpmnReceiveTask -> {
                    taskElementsById.taskElement(node.id).setAttribute("messageRef", node.messageRef)
                }

                // decisionRef on business-rule tasks is not a BPMN-spec attribute. Emit it in
                // our extension namespace so XSD validation (which allows `##other` foreign
                // attributes on tBusinessRuleTask) accepts it.
                is BpmnBusinessRuleTask -> {
                    taskElementsById.taskElement(node.id).setAttributeNS(
                        BPMNER_EXT_NS,
                        "bpmner:decisionRef",
                        node.decisionRef,
                    )
                    bpmnerNamespaceUsed = true
                }

                else -> {
                    Unit
                }
            }
        }

        // Multi-instance marker applies to every task kind, so stamp it in a dedicated pass over
        // all task elements rather than widening the attribute `when` above with seven arms.
        val allTaskElementsById = document.allTaskElementsById()
        definition.nodes.filterIsInstance<BpmnTask>().forEach { task ->
            task.multiInstance?.let { mi ->
                allTaskElementsById[task.id]?.appendMultiInstance(document, mi)
                // appendMultiInstance carries collectionDescription in our extension namespace.
                bpmnerNamespaceUsed = true
            }
        }

        // Text annotations and associations are process-level artifacts (after flowElements in the
        // tProcess XSD sequence), so append them as the last children of <process>.
        definition.annotations.forEach { annotation ->
            process.appendChild(
                document.bpmnElement("textAnnotation").also { el ->
                    el.setAttribute("id", annotation.id)
                    el.appendChild(document.bpmnElement("text").also { it.textContent = annotation.text })
                },
            )
        }
        definition.associations.forEach { association ->
            process.appendChild(
                document.bpmnElement("association").also { el ->
                    el.setAttribute("id", association.id)
                    el.setAttribute("sourceRef", association.sourceRef)
                    el.setAttribute("targetRef", association.targetRef)
                },
            )
        }

        if (bpmnerNamespaceUsed) {
            root.setAttributeNS(
                "http://www.w3.org/2000/xmlns/",
                "xmlns:bpmner",
                BPMNER_EXT_NS,
            )
        }

        return writeDocument(document)
    }

    // Only the task kinds whose attribute payloads we DOM-stamp post-Camunda-write. Script /
    // Manual / User / Service tasks have no payload attribute to apply here today, so we keep
    // the list narrow to match `addCatalogsAndEventDefinitions`'s `when` arms. When a new task
    // kind gains a DOM-stamp arm, add its element name here too — the writer-side `when` is
    // the source of truth for which tasks need post-processing.
    private fun Document.taskElementsById(): Map<String, Element> = listOf("sendTask", "receiveTask", "businessRuleTask")
        .flatMap { getElementsByTagNameNS(BPMN_NS, it).elements().toList() }
        .associateBy { it.getAttribute("id") }
        .filterKeys { it.isNotBlank() }

    // Every task kind may carry a multi-instance marker, so this index spans all seven task
    // element names (unlike `taskElementsById`, which is scoped to attribute-payload tasks).
    private fun Document.allTaskElementsById(): Map<String, Element> = ALL_TASK_LOCAL_NAMES
        .flatMap { getElementsByTagNameNS(BPMN_NS, it).elements().toList() }
        .associateBy { it.getAttribute("id") }
        .filterKeys { it.isNotBlank() }

    // Appends `<bpmn:multiInstanceLoopCharacteristics>` as the last child of a task element —
    // its position in the tActivity XSD sequence (after incoming/outgoing). isSequential=true for
    // SEQUENTIAL, false for PARALLEL. collectionDescription has no native BPMN slot; it rides on
    // the linked text annotation instead.
    private fun Element.appendMultiInstance(
        document: Document,
        mi: MultiInstanceLoopCharacteristics,
    ) {
        appendChild(
            document.bpmnElement("multiInstanceLoopCharacteristics").also { loop ->
                loop.setAttribute("isSequential", (mi.mode == MultiInstanceMode.SEQUENTIAL).toString())
                // collectionDescription has no BPMN-spec home, so carry it in our extension
                // namespace (same approach as decisionRef) to round-trip the domain field.
                loop.setAttributeNS(BPMNER_EXT_NS, "bpmner:collectionDescription", mi.collectionDescription)
                mi.loopCardinality?.let { cardinality ->
                    loop.appendChild(
                        document.bpmnElement("loopCardinality").also { it.textContent = cardinality.toString() },
                    )
                }
                mi.completionCondition?.takeIf { it.isNotBlank() }?.let { condition ->
                    loop.appendChild(
                        document.bpmnElement("completionCondition").also { it.textContent = condition },
                    )
                }
            },
        )
    }

    private fun Map<String, Element>.taskElement(id: String): Element {
        return this[id] ?: error("Task element with id='$id' not found in rendered XML")
    }

    private fun parseDocument(xml: String): Document = DocumentBuilderFactory
        .newInstance()
        .also {
            it.isNamespaceAware = true
            it.setFeature(DISALLOW_DOCTYPE_DECL, true)
            it.setFeature(EXTERNAL_GENERAL_ENTITIES, false)
            it.setFeature(EXTERNAL_PARAMETER_ENTITIES, false)
            it.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            it.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
            it.isXIncludeAware = false
            it.isExpandEntityReferences = false
        }.newDocumentBuilder()
        .parse(org.xml.sax.InputSource(StringReader(xml)))

    private fun writeDocument(document: Document): String {
        val writer = StringWriter()
        TransformerFactory
            .newInstance()
            .also {
                it.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                it.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
            }.newTransformer()
            .also {
                it.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                it.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }

    private fun Document.bpmnElement(localName: String): Element = createElementNS(BPMN_NS, "bpmn:$localName")

    private fun Document.eventElementsById(): Map<String, Element> = listOf(
        "startEvent",
        "intermediateCatchEvent",
        "intermediateThrowEvent",
        "boundaryEvent",
        "endEvent",
    )
        .flatMap { getElementsByTagNameNS(BPMN_NS, it).elements().toList() }
        .associateBy { it.getAttribute("id") }
        .filterKeys { it.isNotBlank() }

    private fun Map<String, Element>.eventElement(id: String): Element {
        return this[id] ?: error("Unable to locate BPMN event element id=\"$id\" in generated BPMN XML")
    }

    private fun org.w3c.dom.NodeList.elements(): Sequence<Element> = sequence {
        for (index in 0 until length) {
            (item(index) as? Element)?.let { yield(it) }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun Element.appendEventDefinition(
        document: Document,
        definition: BpmnEventDefinition,
    ) {
        if (definition is BpmnNoneEventDefinition) return
        appendChild(
            when (definition) {
                is BpmnTimerEventDefinition -> {
                    document.bpmnElement("timerEventDefinition").also { event ->
                        val childName =
                            when (definition.timerKind) {
                                BpmnTimerKind.DATE -> "timeDate"
                                BpmnTimerKind.DURATION -> "timeDuration"
                                BpmnTimerKind.CYCLE -> "timeCycle"
                            }
                        event.appendChild(
                            document.bpmnElement(childName).also {
                                it.textContent = definition.expression
                            },
                        )
                    }
                }

                is BpmnMessageEventDefinition -> {
                    document.bpmnElement("messageEventDefinition").also {
                        it.setAttribute("messageRef", definition.messageRef)
                    }
                }

                is BpmnSignalEventDefinition -> {
                    document.bpmnElement("signalEventDefinition").also {
                        it.setAttribute("signalRef", definition.signalRef)
                    }
                }

                is BpmnErrorEventDefinition -> {
                    document.bpmnElement("errorEventDefinition").also {
                        it.setAttribute("errorRef", definition.errorRef)
                    }
                }

                is BpmnEscalationEventDefinition -> {
                    document.bpmnElement("escalationEventDefinition").also {
                        it.setAttribute("escalationRef", definition.escalationRef)
                    }
                }

                is BpmnTerminateEventDefinition -> {
                    document.bpmnElement("terminateEventDefinition")
                }

                is BpmnNoneEventDefinition -> {
                    error("none event definition must not render XML")
                }

                // Unrecognized event definitions have no Camunda equivalent and cannot
                // round-trip. The generator pipeline is expected to filter them out before
                // reaching XML emission; reaching here is a contract violation.
                is BpmnUnrecognizedEventDefinition -> {
                    error(
                        "BpmnUnrecognizedEventDefinition (${definition.typeName}) cannot be rendered to XML. " +
                            "The generator must filter unrecognized event definitions before reaching this point.",
                    )
                }
            },
        )
    }
}
