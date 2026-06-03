/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.core.BpmnAssociation
import dev.groknull.bpmner.core.BpmnBoundaryEvent
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorEventDefinition
import dev.groknull.bpmner.core.BpmnErrorRef
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnEventBasedGateway
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnGroup
import dev.groknull.bpmner.core.BpmnInclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnScriptTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnTextAnnotation
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedNode
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.core.StandardLoopCharacteristics
import dev.groknull.bpmner.generation.BpmnXmlParser
import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.EventBasedGateway
import org.camunda.bpm.model.bpmn.instance.ExclusiveGateway
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway
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
                )
            }

        val artifacts = artifactsFrom(document)
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
            diagramCount = diagramCount,
        )
    }

    // Text annotations and their association edges, parsed together (one helper keeps the class
    // function count in check). BPMN models the link as sourceRef = annotated element,
    // targetRef = annotation.
    private data class Artifacts(
        val annotations: List<BpmnTextAnnotation>,
        val groups: List<BpmnGroup>,
        val associations: List<BpmnAssociation>,
    )

    private fun artifactsFrom(document: Document): Artifacts {
        val categoryValuesById = document
            .bpmnElements("categoryValue")
            .associate { it.getAttribute("id") to it.getAttribute("value") }
            .filter { (id, value) -> id.isNotBlank() && value.isNotBlank() }
        val annotations = document
            .bpmnElements("textAnnotation")
            .map { el ->
                BpmnTextAnnotation(
                    id = el.getAttribute("id"),
                    text = el.childElements().firstOrNull { it.localName == "text" }?.textContent?.trim().orEmpty(),
                )
            }.filter { it.id.isNotBlank() }
            .toList()
        val groups = document
            .bpmnElements("group")
            .map { el ->
                val categoryValueRef = el.getAttribute("categoryValueRef").localNameRef()
                BpmnGroup(
                    id = el.getAttribute("id"),
                    name = categoryValueRef?.let { categoryValuesById[it] },
                )
            }.filter { it.id.isNotBlank() }
            .toList()
        val associations = document
            .bpmnElements("association")
            .map { el ->
                BpmnAssociation(
                    id = el.getAttribute("id"),
                    sourceRef = el.getAttribute("sourceRef"),
                    targetRef = el.getAttribute("targetRef"),
                )
            }.filter { it.id.isNotBlank() }
            .toList()
        return Artifacts(annotations = annotations, groups = groups, associations = associations)
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
                BpmnUserTask(
                    id = id,
                    name = normalisedName,
                    multiInstance = taskMetadata.multiInstance[id],
                    standardLoop = taskMetadata.standardLoop[id],
                )
            }

            is ServiceTask -> {
                BpmnServiceTask(
                    id = id,
                    name = normalisedName,
                    multiInstance = taskMetadata.multiInstance[id],
                    standardLoop = taskMetadata.standardLoop[id],
                )
            }

            is ScriptTask -> {
                BpmnScriptTask(
                    id = id,
                    name = normalisedName,
                    multiInstance = taskMetadata.multiInstance[id],
                    standardLoop = taskMetadata.standardLoop[id],
                )
            }

            is BusinessRuleTask -> {
                BpmnBusinessRuleTask(
                    id = id,
                    name = normalisedName,
                    decisionRef = taskMetadata.decisionRefs[id].orEmpty(),
                    multiInstance = taskMetadata.multiInstance[id],
                    standardLoop = taskMetadata.standardLoop[id],
                )
            }

            is SendTask -> {
                BpmnSendTask(
                    id = id,
                    name = normalisedName,
                    messageRef = taskMetadata.messageRefs[id].orEmpty(),
                    multiInstance = taskMetadata.multiInstance[id],
                    standardLoop = taskMetadata.standardLoop[id],
                )
            }

            is ReceiveTask -> {
                BpmnReceiveTask(
                    id = id,
                    name = normalisedName,
                    messageRef = taskMetadata.messageRefs[id].orEmpty(),
                    multiInstance = taskMetadata.multiInstance[id],
                    standardLoop = taskMetadata.standardLoop[id],
                )
            }

            is ManualTask -> {
                BpmnManualTask(
                    id = id,
                    name = normalisedName,
                    multiInstance = taskMetadata.multiInstance[id],
                    standardLoop = taskMetadata.standardLoop[id],
                )
            }

            is ExclusiveGateway -> {
                BpmnExclusiveGateway(id = id, name = normalisedName)
            }

            is InclusiveGateway -> {
                BpmnInclusiveGateway(id = id, name = normalisedName)
            }

            is ParallelGateway -> {
                BpmnParallelGateway(id = id, name = normalisedName)
            }

            is EventBasedGateway -> {
                BpmnEventBasedGateway(id = id, name = normalisedName)
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

            // FlowNode subtypes the parser doesn't translate (Transaction, CallActivity,
            // SubProcess, etc.) are surfaced as `BpmnUnrecognizedNode` so the `BpmnSubset`
            // rule can flag them. Policy stays in the rule engine.
            else -> {
                BpmnUnrecognizedNode(
                    id = id,
                    name = normalisedName,
                    bpmnType = "bpmn:${elementType.typeName.replaceFirstChar { it.uppercase() }}",
                )
            }
        }
    }

    private data class TaskMetadata(
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

private fun String.localNameRef(): String? = trim()
    .takeIf { it.isNotBlank() }
    ?.substringAfterLast(":")
