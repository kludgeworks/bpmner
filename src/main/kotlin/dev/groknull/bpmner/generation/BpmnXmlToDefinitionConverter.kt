/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnCollaboration
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnErrorRef
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnLane
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnMessageFlow
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnPool
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnScriptTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.generation.BpmnXmlParser
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.IntermediateCatchEvent
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent
import org.camunda.bpm.model.bpmn.instance.ManualTask
import org.camunda.bpm.model.bpmn.instance.ParallelGateway
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.ReceiveTask
import org.camunda.bpm.model.bpmn.instance.ScriptTask
import org.camunda.bpm.model.bpmn.instance.SendTask
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.ServiceTask
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.UserTask
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

@SecondaryAdapter
@Component
@Suppress("TooManyFunctions") // per-element parse helpers + single-process and collaboration entry points
internal open class BpmnXmlToDefinitionConverter : BpmnXmlParser {
    companion object {
        private const val BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"

        // Foreign-namespace extension prefix for attributes we read off flow elements that the
        // BPMN 2.0 spec doesn't define (e.g. `decisionRef` on businessRuleTask). The canonical
        // URI lives on the writer side as [BpmnDefinitionToXmlConverter.BPMNER_EXT_NS]; we
        // alias it here so the reader stays in lockstep with the writer and a URI change in
        // one place can't silently dissociate the round-trip.
        private const val BPMNER_EXT_NS = BpmnDefinitionToXmlConverter.BPMNER_EXT_NS
        private const val DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl"
        private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
        private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"
    }

    override fun parse(xml: String): BpmnDefinition {
        val document = parseDocument(xml)
        val model: BpmnModelInstance = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        rejectIfHasDiagramInterchange(model)

        val process =
            model.getModelElementsByType(Process::class.java).firstOrNull()
                ?: error("BPMN XML contains no <process> element")

        val eventMetadata = eventMetadataFrom(document)
        val taskMetadata = taskMetadataFrom(document)
        // Single-process input: the document's definitions-level catalogs all belong to this one
        // process, so attach them here.
        return parseProcess(process, eventMetadata, taskMetadata, includeCatalogs = true)
    }

    /**
     * Parses a `<collaboration>` document into a [BpmnCollaboration]: one [BpmnPool] per
     * participant (each wrapping its referenced process) plus the cross-pool message flows.
     * Note: definitions-level catalogs (message/signal/error/escalation) can't be attributed to a
     * specific pool on read-back, so participant processes are parsed without them — round-tripping
     * a collaboration that declares catalogs is a known limitation handled when the generator wires
     * collaborations end-to-end.
     */
    fun parseCollaboration(xml: String): BpmnCollaboration {
        val document = parseDocument(xml)
        val model: BpmnModelInstance = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        rejectIfHasDiagramInterchange(model)

        val collaboration =
            model.getModelElementsByType(Collaboration::class.java).firstOrNull()
                ?: error("BPMN XML contains no <collaboration> element")

        val eventMetadata = eventMetadataFrom(document)
        val taskMetadata = taskMetadataFrom(document)

        val pools =
            collaboration.participants.map { participant ->
                BpmnPool(
                    id = participant.id,
                    name = participant.name?.takeIf { it.isNotBlank() } ?: participant.id,
                    process = parseProcess(participant.process, eventMetadata, taskMetadata, includeCatalogs = false),
                )
            }
        val messageFlows =
            collaboration.messageFlows.map { flow ->
                BpmnMessageFlow(id = flow.id, sourceRef = flow.source.id, targetRef = flow.target.id)
            }
        return BpmnCollaboration(id = collaboration.id, participants = pools, messageFlows = messageFlows)
    }

    private fun parseProcess(
        process: Process,
        eventMetadata: EventMetadata,
        taskMetadata: TaskMetadata,
        includeCatalogs: Boolean,
    ): BpmnDefinition {
        val nodes =
            process.getChildElementsByType(FlowNode::class.java).map { it.toBpmnNode(eventMetadata, taskMetadata) }
        val sequences =
            process.getChildElementsByType(SequenceFlow::class.java).map { flow ->
                BpmnEdge(
                    id = flow.id,
                    sourceRef = flow.source.id,
                    targetRef = flow.target.id,
                    name = flow.name?.takeIf { it.isNotBlank() },
                    conditionExpression = flow.conditionExpression?.textContent?.takeIf { it.isNotBlank() },
                    isDefault = (flow.source as? ExclusiveGateway)?.default?.id == flow.id,
                )
            }
        return BpmnDefinition(
            processId = process.id,
            processName = process.name?.takeIf { it.isNotBlank() } ?: process.id,
            nodes = nodes,
            sequences = sequences,
            messages = if (includeCatalogs) eventMetadata.messages else emptyList(),
            signals = if (includeCatalogs) eventMetadata.signals else emptyList(),
            errors = if (includeCatalogs) eventMetadata.errors else emptyList(),
            escalations = if (includeCatalogs) eventMetadata.escalations else emptyList(),
            lanes =
            process.laneSets.flatMap { it.lanes }.map { lane ->
                BpmnLane(
                    id = lane.id,
                    name = lane.name?.takeIf { it.isNotBlank() } ?: lane.id,
                    flowNodeRefs = lane.flowNodeRefs.map { it.id },
                )
            },
        )
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

    private fun rejectIfHasDiagramInterchange(model: BpmnModelInstance) {
        require(model.getModelElementsByType(BpmnDiagram::class.java).isEmpty()) {
            "BPMNDI input rejected — semantic-only XML required. " +
                "Strip <bpmndi:BPMNDiagram> elements before parsing."
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun FlowNode.toBpmnNode(
        eventMetadata: EventMetadata,
        taskMetadata: TaskMetadata,
    ): BpmnNode {
        val normalisedName = name?.takeIf { it.isNotBlank() }
        return when (this) {
            is StartEvent -> {
                BpmnStartEvent(
                    id = id,
                    name = normalisedName,
                    eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                    isInterrupting = eventMetadata.isInterrupting[id] ?: true,
                )
            }

            is UserTask -> {
                BpmnUserTask(id = id, name = normalisedName)
            }

            is ServiceTask -> {
                BpmnServiceTask(id = id, name = normalisedName)
            }

            is ScriptTask -> {
                BpmnScriptTask(id = id, name = normalisedName)
            }

            is BusinessRuleTask -> {
                BpmnBusinessRuleTask(
                    id = id,
                    name = normalisedName,
                    decisionRef = taskMetadata.decisionRefs[id].orEmpty(),
                )
            }

            is SendTask -> {
                BpmnSendTask(
                    id = id,
                    name = normalisedName,
                    messageRef = taskMetadata.messageRefs[id].orEmpty(),
                )
            }

            is ReceiveTask -> {
                BpmnReceiveTask(
                    id = id,
                    name = normalisedName,
                    messageRef = taskMetadata.messageRefs[id].orEmpty(),
                )
            }

            is ManualTask -> {
                BpmnManualTask(id = id, name = normalisedName)
            }

            is ExclusiveGateway -> {
                BpmnExclusiveGateway(id = id, name = normalisedName)
            }

            is ParallelGateway -> {
                BpmnParallelGateway(id = id, name = normalisedName)
            }

            is IntermediateCatchEvent -> {
                BpmnIntermediateCatchEvent(
                    id = id,
                    name = normalisedName,
                    eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                )
            }

            is IntermediateThrowEvent -> {
                BpmnIntermediateThrowEvent(
                    id = id,
                    name = normalisedName,
                    eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                )
            }

            is BoundaryEvent -> {
                BpmnBoundaryEvent(
                    id = id,
                    name = normalisedName,
                    attachedToRef = eventMetadata.attachedToRefs[id].orEmpty(),
                    cancelActivity = eventMetadata.cancelActivity[id] ?: true,
                    eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                )
            }

            is EndEvent -> {
                BpmnEndEvent(
                    id = id,
                    name = normalisedName,
                    eventDefinition = eventMetadata.eventDefinitions[id] ?: BpmnNoneEventDefinition,
                )
            }

            else -> {
                error("Unsupported BPMN flow node type for id='$id': ${this::class.simpleName}")
            }
        }
    }

    private data class TaskMetadata(
        // `messageRef` on send / receive tasks — BPMN spec attribute on the task element.
        val messageRefs: Map<String, String>,
        // `bpmner:decisionRef` on business-rule tasks — foreign-namespace extension since the
        // spec defines no decisionRef on tBusinessRuleTask. See [BpmnDefinitionToXmlConverter.BPMNER_EXT_NS].
        val decisionRefs: Map<String, String>,
    )

    private fun taskMetadataFrom(document: Document): TaskMetadata {
        val sendReceive =
            (document.bpmnElements("sendTask").toList() + document.bpmnElements("receiveTask").toList())
                .associate { it.getAttribute("id") to it.getAttribute("messageRef") }
                .filter { (id, ref) -> id.isNotBlank() && ref.isNotBlank() }
        val businessRule =
            document
                .bpmnElements("businessRuleTask")
                .associate { it.getAttribute("id") to it.getAttributeNS(BPMNER_EXT_NS, "decisionRef") }
                .filter { (id, ref) -> id.isNotBlank() && ref.isNotBlank() }
        return TaskMetadata(messageRefs = sendReceive, decisionRefs = businessRule)
    }

    private data class EventMetadata(
        val eventDefinitions: Map<String, BpmnEventDefinition>,
        val isInterrupting: Map<String, Boolean>,
        val attachedToRefs: Map<String, String>,
        val cancelActivity: Map<String, Boolean>,
        val messages: List<BpmnMessageRef>,
        val signals: List<BpmnSignalRef>,
        val errors: List<BpmnErrorRef>,
        val escalations: List<BpmnEscalationRef>,
    )

    @Suppress("LongMethod")
    private fun eventMetadataFrom(document: Document): EventMetadata {
        val eventElements =
            listOf("startEvent", "intermediateCatchEvent", "intermediateThrowEvent", "boundaryEvent", "endEvent")
                .flatMap { document.bpmnElements(it).toList() }
        return EventMetadata(
            eventDefinitions =
            eventElements
                .associate { it.getAttribute("id") to it.eventDefinition() }
                .filterKeys { it.isNotBlank() },
            isInterrupting =
            document
                .bpmnElements("startEvent")
                .filter { it.hasAttribute("isInterrupting") }
                .associate { it.getAttribute("id") to it.getAttribute("isInterrupting").toBoolean() },
            attachedToRefs =
            document
                .bpmnElements("boundaryEvent")
                .associate { it.getAttribute("id") to it.getAttribute("attachedToRef") }
                .filterValues { it.isNotBlank() },
            cancelActivity =
            document
                .bpmnElements("boundaryEvent")
                .filter { it.hasAttribute("cancelActivity") }
                .associate { it.getAttribute("id") to it.getAttribute("cancelActivity").toBoolean() },
            messages =
            document
                .bpmnElements("message")
                .map { BpmnMessageRef(id = it.getAttribute("id"), name = it.getAttribute("name")) }
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .toList(),
            signals =
            document
                .bpmnElements("signal")
                .map { BpmnSignalRef(id = it.getAttribute("id"), name = it.getAttribute("name")) }
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .toList(),
            errors =
            document
                .bpmnElements("error")
                .map {
                    BpmnErrorRef(
                        id = it.getAttribute("id"),
                        code = it.getAttribute("errorCode"),
                        name = it.getAttribute("name").takeIf { name -> name.isNotBlank() },
                    )
                }.filter { it.id.isNotBlank() && it.code.isNotBlank() }
                .toList(),
            escalations =
            document
                .bpmnElements("escalation")
                .map {
                    BpmnEscalationRef(
                        id = it.getAttribute("id"),
                        code = it.getAttribute("escalationCode"),
                        name = it.getAttribute("name").takeIf { name -> name.isNotBlank() },
                    )
                }.filter { it.id.isNotBlank() && it.code.isNotBlank() }
                .toList(),
        )
    }

    @Suppress("MaxLineLength")
    private fun Document.bpmnElements(localName: String): Sequence<Element> = getElementsByTagNameNS(BPMN_NS, localName).elements()

    private fun org.w3c.dom.NodeList.elements(): Sequence<Element> = sequence {
        for (index in 0 until length) {
            (item(index) as? Element)?.let { yield(it) }
        }
    }

    private fun Element.eventDefinition(): BpmnEventDefinition {
        val child =
            childElements().firstOrNull { it.localName?.endsWith("EventDefinition") == true }
                ?: return BpmnNoneEventDefinition
        return when (child.localName) {
            "timerEventDefinition" -> child.timerEventDefinition(getAttribute("id"))
            "messageEventDefinition" -> BpmnMessageEventDefinition(child.getAttribute("messageRef"))
            "signalEventDefinition" -> BpmnSignalEventDefinition(child.getAttribute("signalRef"))
            "errorEventDefinition" -> BpmnErrorEventDefinition(child.getAttribute("errorRef"))
            "escalationEventDefinition" -> BpmnEscalationEventDefinition(child.getAttribute("escalationRef"))
            "terminateEventDefinition" -> BpmnTerminateEventDefinition
            else -> BpmnNoneEventDefinition
        }
    }

    private fun Element.timerEventDefinition(eventId: String): BpmnTimerEventDefinition {
        val timerElement =
            childElements().firstOrNull {
                it.localName == "timeDate" || it.localName == "timeDuration" || it.localName == "timeCycle"
            } ?: throw IllegalArgumentException(
                "Malformed timerEventDefinition for event '${eventId.ifBlank { "<unknown>" }}': " +
                    "expected timeDate, timeDuration, or timeCycle child",
            )
        val kind =
            when (timerElement.localName) {
                "timeDate" -> BpmnTimerKind.DATE

                "timeDuration" -> BpmnTimerKind.DURATION

                "timeCycle" -> BpmnTimerKind.CYCLE

                // The filter above only admits timeDate/timeDuration/timeCycle and throws if none
                // match — this branch cannot fire today. The explicit error makes the invariant
                // load-bearing so a future filter loosening can't silently default to DATE.
                else -> error("unreachable: timerElement.localName='${timerElement.localName}'")
            }
        return BpmnTimerEventDefinition(kind, timerElement.textContent.orEmpty())
    }

    private fun Element.childElements(): Sequence<Element> = childNodes.elements()
}
