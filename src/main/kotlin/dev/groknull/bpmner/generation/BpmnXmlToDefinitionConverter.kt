/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.DataFlowDirection
import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.core.BpmnAssociation
import dev.groknull.bpmner.core.BpmnDataAssociation
import dev.groknull.bpmner.core.BpmnDataObject
import dev.groknull.bpmner.core.BpmnDataStore
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnErrorRef
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnGroup
import dev.groknull.bpmner.core.BpmnLane
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnMessageFlow
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnParticipant
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnTextAnnotation
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedNode
import dev.groknull.bpmner.core.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.core.StandardLoopCharacteristics
import dev.groknull.bpmner.generation.BpmnXmlParser
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

// The non-flow-node artifacts parsed from a BPMN document in one pass, kept together so the parser
// surfaces them with a single helper.
private data class ParsedArtifacts(
    val annotations: List<BpmnTextAnnotation>,
    val associations: List<BpmnAssociation>,
    val groups: List<BpmnGroup>,
    val dataObjects: List<BpmnDataObject>,
    val dataStores: List<BpmnDataStore>,
    val dataAssociations: List<BpmnDataAssociation>,
)

// Collaboration artifacts: participants (pools) + message flows from <collaboration>, and lanes from
// each process's <laneSet>. Parsed top-level (off the converter class) so the projection stays a
// single pass without inflating the class's function count.
private data class ParsedCollaboration(
    val participants: List<BpmnParticipant>,
    val lanes: List<BpmnLane>,
    val messageFlows: List<BpmnMessageFlow>,
)

@SecondaryAdapter
@Component
internal open class BpmnXmlToDefinitionConverter : BpmnXmlParser {
    companion object {
        internal const val BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"

        // Foreign-namespace extension prefix for attributes we read off flow elements that the
        // BPMN 2.0 spec doesn't define (e.g. `decisionRef` on businessRuleTask). The canonical
        // URI lives on the writer side as [BpmnDefinitionToXmlConverter.BPMNER_EXT_NS]; we
        // alias it here so the reader stays in lockstep with the writer and a URI change in
        // one place can't silently dissociate the round-trip.
        private const val BPMNER_EXT_NS = BpmnDefinitionToXmlConverter.BPMNER_EXT_NS
        private const val DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl"
        private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
        private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"

        // BPMN element local names that aren't `FlowNode`s (so the Camunda model walk misses
        // them) and that the parser doesn't translate into typed Kotlin nodes. Surfaced via
        // DOM scan as `BpmnUnrecognizedNode` so the rule engine can flag them through
        // `BpmnSubset`'s `targetElements`. The parser surfaces all such elements; the Pkl
        // rule decides what's discouraged. Includes both top-level constructs (Choreography,
        // Conversation) and their child element types (e.g. choreographyTask), all picked up
        // by `getElementsByTagNameNS` regardless of nesting.
        private val EXOTIC_BPMN_LOCAL_NAMES = listOf(
            "choreography",
            "choreographyTask",
            "subChoreography",
            "callChoreography",
            "conversation",
            "conversationLink",
            "conversationAssociation",
        )
    }

    override fun parse(xml: String): BpmnDefinition {
        val document = parseDocument(xml)
        val model: BpmnModelInstance = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        // Counts `<bpmndi:BPMNDiagram>` elements. The semantic model carries no DI; the count
        // is surfaced on `BpmnDefinition` so the `NoDuplicateDiagrams` rule can enforce the
        // policy (one diagram per document).
        val diagramCount = model.getModelElementsByType(BpmnDiagram::class.java).size

        val process =
            model.getModelElementsByType(Process::class.java).firstOrNull()
                ?: error("BPMN XML contains no <process> element")

        val eventMetadata = eventMetadataFrom(document)
        val taskMetadata = taskMetadataFrom(document)
        val typedNodes = model.getModelElementsByType(FlowNode::class.java).map { it.toBpmnNode(eventMetadata, taskMetadata) }
        // Exotic constructs (Choreography, Conversation, etc.) aren't `FlowNode`s and miss the
        // typed scan above. Surface them as `BpmnUnrecognizedNode` so the `BpmnSubset` rule can
        // flag them via `targetElements`. The parser surfaces all such elements; the rule
        // decides what's discouraged. Fallback ids are deterministic per-document so two parses
        // of the same XML produce the same `elementId`s.
        val unrecognizedExotics = EXOTIC_BPMN_LOCAL_NAMES.flatMapIndexed { typeIndex, localName ->
            document.bpmnElements(localName).mapIndexed { elemIndex, element ->
                BpmnUnrecognizedNode(
                    id = element.getAttribute("id").ifBlank { "${localName}_${typeIndex}_$elemIndex" },
                    name = element.getAttribute("name").takeIf { it.isNotBlank() },
                    bpmnType = "bpmn:${localName.replaceFirstChar { it.uppercase() }}",
                )
            }.toList()
        }

        val sequences =
            model.getModelElementsByType(SequenceFlow::class.java).map { flow ->
                BpmnEdge(
                    id = flow.id,
                    sourceRef = flow.source.id,
                    targetRef = flow.target.id,
                    name = flow.name?.takeIf { it.isNotBlank() },
                    conditionExpression = flow.conditionExpression?.textContent?.takeIf { it.isNotBlank() },
                    isDefault = (flow.source as? ExclusiveGateway)?.default?.id == flow.id ||
                        (flow.source as? InclusiveGateway)?.default?.id == flow.id,
                    parentRef = (flow.parentElement as? SubProcess)?.id,
                )
            }

        val artifacts = artifactsAndDataFrom(document)
        val collaboration = parseCollaboration(document)
        return BpmnDefinition(
            processId = process.id,
            processName = process.name?.takeIf { it.isNotBlank() } ?: process.id,
            nodes = typedNodes + unrecognizedExotics,
            sequences = sequences,
            messages = eventMetadata.messages,
            signals = eventMetadata.signals,
            errors = eventMetadata.errors,
            escalations = eventMetadata.escalations,
            annotations = artifacts.annotations,
            groups = artifacts.groups,
            associations = artifacts.associations,
            dataObjects = artifacts.dataObjects,
            dataStores = artifacts.dataStores,
            dataAssociations = artifacts.dataAssociations,
            participants = collaboration.participants,
            lanes = collaboration.lanes,
            messageFlows = collaboration.messageFlows,
            diagramCount = diagramCount,
        )
    }

    // All non-flow-node artifacts in one pass (one helper keeps the class function count in check):
    // text annotations + their association edges (sourceRef = annotated element, targetRef =
    // annotation), groups, data objects/stores, and the read/write data associations parsed from
    // each activity's `<dataInputAssociation>` (data id in `<sourceRef>`) /
    // `<dataOutputAssociation>` (data id in `<targetRef>`) children (the association's `sourceRef`
    // is the parent activity id).
    private fun artifactsAndDataFrom(document: Document): ParsedArtifacts {
        val categoryValuesById = document.bpmnElements("categoryValue")
            .mapNotNull { el ->
                val id = el.getAttribute("id")
                val value = el.getAttribute("value")
                if (id.isNotBlank() && value.isNotBlank()) id to value else null
            }.toMap()
        val annotations = document.bpmnElements("textAnnotation")
            .map { el ->
                BpmnTextAnnotation(
                    id = el.getAttribute("id"),
                    text = el.childElements().firstOrNull { it.localName == "text" }?.textContent?.trim().orEmpty(),
                )
            }.filter { it.id.isNotBlank() }.toList()
        val groups = document.bpmnElements("group")
            .map { el ->
                val categoryValueRef = el.getAttribute("categoryValueRef").localNameRef()
                BpmnGroup(
                    id = el.getAttribute("id"),
                    name = categoryValueRef?.let { categoryValuesById[it] },
                )
            }.filter { it.id.isNotBlank() }.toList()
        val associations = document.bpmnElements("association")
            .map { el -> BpmnAssociation(el.getAttribute("id"), el.getAttribute("sourceRef"), el.getAttribute("targetRef")) }
            .filter { it.id.isNotBlank() }.toList()
        val dataObjects = document.bpmnElements("dataObject")
            .map { BpmnDataObject(id = it.getAttribute("id"), name = it.getAttribute("name")) }
            .filter { it.id.isNotBlank() }.toList()
        val dataStores = document.bpmnElements("dataStore")
            .map { BpmnDataStore(id = it.getAttribute("id"), name = it.getAttribute("name")) }
            .filter { it.id.isNotBlank() }.toList()
        fun Element.toAssociation(direction: DataFlowDirection, refChild: String): BpmnDataAssociation? {
            val activityId = (parentNode as? Element)?.getAttribute("id").orEmpty()
            val dataId = childElements().firstOrNull { it.localName == refChild }?.textContent?.trim().orEmpty()
            // Externally-authored BPMN may omit the association id; derive a deterministic one so the
            // link still round-trips. Skip only when the endpoints (activity / data id) are missing.
            val id = getAttribute("id").ifBlank { "DataAssoc_${activityId}_$dataId" }
            return if (activityId.isBlank() || dataId.isBlank()) {
                null
            } else {
                BpmnDataAssociation(id = id, sourceRef = activityId, targetRef = dataId, direction = direction)
            }
        }
        val inputs = document.bpmnElements("dataInputAssociation")
            .mapNotNull { it.toAssociation(DataFlowDirection.READ, "sourceRef") }
        val outputs = document.bpmnElements("dataOutputAssociation")
            .mapNotNull { it.toAssociation(DataFlowDirection.WRITE, "targetRef") }
        return ParsedArtifacts(annotations, associations, groups, dataObjects, dataStores, (inputs + outputs).toList())
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

    private fun FlowNode.toBpmnNode(
        eventMetadata: EventMetadata,
        taskMetadata: TaskMetadata,
    ): BpmnNode {
        val normalisedName = name?.takeIf { it.isNotBlank() }
        // Camunda's document-wide FlowNode scan returns nested nodes too; their `parentElement`
        // is the enclosing <subProcess>. A top-level node's parent is the <process>, giving null.
        val parentRef = (parentElement as? SubProcess)?.id
        return toBpmnTaskOrNull(normalisedName, parentRef, taskMetadata)
            ?: toBpmnEventOrNull(normalisedName, parentRef, eventMetadata)
            ?: toBpmnGatewayOrNull(normalisedName, parentRef)
            ?: toBpmnSubProcessOrUnrecognized(normalisedName, parentRef)
    }

    private fun taskMetadataFrom(document: Document): TaskMetadata {
        // Local helper (kept off the class surface) parsing one task's multi-instance marker.
        fun Element.multiInstanceOrNull(): MultiInstanceLoopCharacteristics? {
            val loop = childElements().firstOrNull { it.localName == "multiInstanceLoopCharacteristics" } ?: return null
            val isSequential = xsdBooleanOrDefault(loop.getAttribute("isSequential"), default = false)
            val mode = if (isSequential) MultiInstanceMode.SEQUENTIAL else MultiInstanceMode.PARALLEL
            return MultiInstanceLoopCharacteristics(
                mode = mode,
                // collectionDescription rides our extension attribute (see the writer); foreign BPMN
                // without it yields an empty description, which downstream validation can flag.
                collectionDescription = loop.getAttributeNS(BPMNER_EXT_NS, "collectionDescription"),
                loopCardinality =
                loop.childElements().firstOrNull { it.localName == "loopCardinality" }?.textContent?.trim()?.toIntOrNull(),
                completionCondition =
                loop.childElements().firstOrNull { it.localName == "completionCondition" }
                    ?.textContent
                    ?.takeIf { it.isNotBlank() },
            )
        }
        fun Element.standardLoopOrNull(): StandardLoopCharacteristics? {
            val loop = childElements().firstOrNull { it.localName == "standardLoopCharacteristics" } ?: return null
            // xsd:boolean; an absent attribute defaults to our domain default (while-loop = true).
            val testBefore = xsdBooleanOrDefault(loop.getAttribute("testBefore"), default = true)
            return StandardLoopCharacteristics(
                testBefore = testBefore,
                loopCondition =
                loop.childElements().firstOrNull { it.localName == "loopCondition" }?.textContent?.takeIf { it.isNotBlank() },
                loopMaximum = loop.getAttribute("loopMaximum").trim().toIntOrNull(),
            )
        }
        val sendReceive =
            (document.bpmnElements("sendTask").toList() + document.bpmnElements("receiveTask").toList())
                .associate { it.getAttribute("id") to it.getAttribute("messageRef") }
                .filter { (id, ref) -> id.isNotBlank() && ref.isNotBlank() }
        val businessRule =
            document
                .bpmnElements("businessRuleTask")
                .associate { it.getAttribute("id") to it.getAttributeNS(BPMNER_EXT_NS, "decisionRef") }
                .filter { (id, ref) -> id.isNotBlank() && ref.isNotBlank() }
        val multiInstance =
            BPMN_TASK_LOCAL_NAMES
                .flatMap { document.bpmnElements(it).toList() }
                .mapNotNull { task -> task.multiInstanceOrNull()?.let { task.getAttribute("id") to it } }
                .filter { (id, _) -> id.isNotBlank() }
                .toMap()
        val standardLoop =
            BPMN_TASK_LOCAL_NAMES
                .flatMap { document.bpmnElements(it).toList() }
                .mapNotNull { task -> task.standardLoopOrNull()?.let { task.getAttribute("id") to it } }
                .filter { (id, _) -> id.isNotBlank() }
                .toMap()
        return TaskMetadata(
            messageRefs = sendReceive,
            decisionRefs = businessRule,
            multiInstance = multiInstance,
            standardLoop = standardLoop,
        )
    }

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
            messages = document.parseMessages(),
            signals = document.parseSignals(),
            errors = document.parseErrors(),
            escalations = document.parseEscalations(),
        )
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

            // Event-definition typenames without a typed Kotlin class (e.g. compensate,
            // cancel) surface as `BpmnUnrecognizedEventDefinition`. The `BpmnSubset` rule
            // matches on the carried typename via `targetElements`.
            else -> BpmnUnrecognizedEventDefinition(
                typeName = "bpmn:${child.localName.replaceFirstChar { it.uppercase() }}",
            )
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

// Parses an xsd:boolean attribute value: "true" / "1" (case-insensitive) → true, "false" / "0" →
// false, blank (absent) → [default]. Top-level so the loop-characteristics parsers share it without
// adding to the converter's method-complexity or class function count.
private fun xsdBooleanOrDefault(raw: String, default: Boolean): Boolean {
    if (raw.isBlank()) return default
    return raw.equals("true", ignoreCase = true) || raw == "1"
}

// Surfaces a FlowNode subtype the parser doesn't translate (CallActivity, Transaction, etc.) as a
// BpmnUnrecognizedNode carrying its BPMN typename. Top-level so it stays off the converter's class
// function count while serving both the SubProcess-variant and the catch-all `else` arm.
internal fun FlowNode.toUnrecognizedNode(
    normalisedName: String?,
    parentRef: String?,
): BpmnUnrecognizedNode = BpmnUnrecognizedNode(
    id = id,
    name = normalisedName,
    bpmnType = "bpmn:${elementType.typeName.replaceFirstChar { it.uppercase() }}",
    parentRef = parentRef,
)

private fun String.localNameRef(): String? = trim()
    .takeIf { it.isNotBlank() }
    ?.substringAfterLast(":")

// BPMN MODEL namespace, duplicated at file scope so the collaboration helpers below stay top-level
// (off the converter class) and don't inflate its function count. Kept in lockstep with the
// converter's companion `BPMN_NS`.
private const val BPMN_MODEL_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"

internal fun Document.bpmnElements(localName: String): Sequence<Element> {
    return getElementsByTagNameNS(BpmnXmlToDefinitionConverter.BPMN_NS, localName).elements()
}

internal fun org.w3c.dom.NodeList.elements(): Sequence<Element> = sequence {
    for (index in 0 until length) {
        (item(index) as? Element)?.let { yield(it) }
    }
}

// Single-line expression body: splitting the qualifier and call across lines adds no readability
// for this thin namespace-scan delegation, so the line-length warning is suppressed deliberately.
@Suppress("MaxLineLength")
private fun Document.bpmnModelElements(localName: String): Sequence<Element> = getElementsByTagNameNS(BPMN_MODEL_NS, localName).elements()

// Walks up to the <process> enclosing this element, returning its id (or null). Binds a <lane> to
// the white-box participant whose processRef names that process.
private fun Element.enclosingProcessId(): String? {
    var node: org.w3c.dom.Node? = parentNode
    while (node != null) {
        if (node is Element && node.localName == "process") {
            return node.getAttribute("id").takeIf { it.isNotBlank() }
        }
        node = node.parentNode
    }
    return null
}

// Parses the collaboration view: participants + message flows from <collaboration>, lanes from each
// <laneSet>. A white-box participant carries processRef; a black-box one omits it. Lane membership is
// the <flowNodeRef> children only; a lane binds to the participant owning its process, or to no
// participant when the process has a lane set without a surrounding collaboration.
private fun parseCollaboration(document: Document): ParsedCollaboration {
    val participants = document.bpmnModelElements("participant")
        .map { el ->
            BpmnParticipant(
                id = el.getAttribute("id"),
                name = el.getAttribute("name").takeIf { it.isNotBlank() },
                processRef = el.getAttribute("processRef").takeIf { it.isNotBlank() },
            )
        }.filter { it.id.isNotBlank() }.toList()
    val participantByProcessId = participants.mapNotNull { p -> p.processRef?.let { it to p.id } }.toMap()
    val lanes = document.bpmnModelElements("lane")
        .map { el ->
            BpmnLane(
                id = el.getAttribute("id"),
                name = el.getAttribute("name").takeIf { it.isNotBlank() },
                participantId = el.enclosingProcessId()?.let { participantByProcessId[it] },
                flowNodeRefs = el.childNodes.elementSequence()
                    .filter { it.localName == "flowNodeRef" }
                    .mapNotNull { it.textContent?.trim()?.takeIf { ref -> ref.isNotBlank() } }
                    .toList(),
            )
        }.filter { it.id.isNotBlank() }.toList()
    val messageFlows = document.bpmnModelElements("messageFlow")
        .map { el ->
            BpmnMessageFlow(
                id = el.getAttribute("id"),
                name = el.getAttribute("name").takeIf { it.isNotBlank() },
                sourceRef = el.getAttribute("sourceRef"),
                targetRef = el.getAttribute("targetRef"),
            )
        }.filter { it.id.isNotBlank() && it.sourceRef.isNotBlank() && it.targetRef.isNotBlank() }.toList()
    return ParsedCollaboration(participants, lanes, messageFlows)
}
=======
internal data class TaskMetadata(
    // `messageRef` on send / receive tasks — BPMN spec attribute on the task element.
    val messageRefs: Map<String, String>,
    // `bpmner:decisionRef` on business-rule tasks — foreign-namespace extension since the
    // spec defines no decisionRef on tBusinessRuleTask. See [BpmnDefinitionToXmlConverter.BPMNER_EXT_NS].
    val decisionRefs: Map<String, String>,
    // Multi-instance loop characteristics, keyed by task id. Applies to any task kind.
    val multiInstance: Map<String, MultiInstanceLoopCharacteristics>,
    // Standard-loop characteristics, keyed by task id. Applies to any task kind.
    val standardLoop: Map<String, StandardLoopCharacteristics>,
)

internal data class EventMetadata(
    val eventDefinitions: Map<String, BpmnEventDefinition>,
    val isInterrupting: Map<String, Boolean>,
    val attachedToRefs: Map<String, String>,
    val cancelActivity: Map<String, Boolean>,
    val messages: List<BpmnMessageRef>,
    val signals: List<BpmnSignalRef>,
    val errors: List<BpmnErrorRef>,
    val escalations: List<BpmnEscalationRef>,
)
>>>>>>> 81b2ee9f (refactor: extract helpers to appease TooManyFunctions and fix CI yaml remarks)
