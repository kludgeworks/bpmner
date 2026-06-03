/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // one @Test per scenario; the count IS the safety property

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
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnEscalationEventDefinition
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnEventBasedGateway
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnInclusiveGateway
import dev.groknull.bpmner.core.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.core.BpmnIntermediateThrowEvent
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTextAnnotation
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUnrecognizedNode
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.core.StandardLoopCharacteristics
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BpmnDefinitionToXmlConverterTest {
    private val converter = BpmnDefinitionToXmlConverter()

    @Test
    fun `converter emits inclusiveGateway for BpmnInclusiveGateway nodes with default flow`() {
        // OR-fork mirroring the fulfilment-with-optional-extras sample: two optional add-on
        // branches with conditions, plus a default flow that fires when neither activates.
        val definition =
            BpmnDefinition(
                processId = "Process_Inclusive",
                processName = "Fulfilment with extras",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Package ready"),
                    BpmnInclusiveGateway("dec-extras", "Which add-ons apply?"),
                    BpmnUserTask("act-wrap", "Apply gift wrap"),
                    BpmnUserTask("act-insert", "Add promotional insert"),
                    BpmnUserTask("act-skip", "Skip add-ons"),
                    BpmnInclusiveGateway("Gateway_join_extras", null),
                    BpmnUserTask("act-label", "Apply shipping label"),
                    BpmnEndEvent("EndEvent_1", "Package shipped"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "dec-extras"),
                    BpmnEdge("F2", "dec-extras", "act-wrap", conditionExpression = "gift wrap requested"),
                    BpmnEdge("F3", "dec-extras", "act-insert", conditionExpression = "qualifies for insert"),
                    BpmnEdge("F4", "dec-extras", "act-skip", isDefault = true),
                    BpmnEdge("F5", "act-wrap", "Gateway_join_extras"),
                    BpmnEdge("F6", "act-insert", "Gateway_join_extras"),
                    BpmnEdge("F7", "act-skip", "Gateway_join_extras"),
                    BpmnEdge("F8", "Gateway_join_extras", "act-label"),
                    BpmnEdge("F9", "act-label", "EndEvent_1"),
                ),
            )

        val xml = converter.render(definition).xml

        // Camunda's writer alphabetises attributes; the diverging gateway has
        // `default="F4"` before `id="dec-extras"` and the join gateway has only `id`.
        assertContains(xml, "<inclusiveGateway default=\"F4\" id=\"dec-extras\"")
        assertContains(xml, "<inclusiveGateway id=\"Gateway_join_extras\"")
        // Conditions are present on the two conditional branches.
        assertContains(xml, "gift wrap requested")
        assertContains(xml, "qualifies for insert")
    }

    @Test
    fun `multi-instance task renders the loop marker, text annotation, and association`() {
        val definition = miDefinition()

        val xml = converter.render(definition).xml

        assertContains(xml, "multiInstanceLoopCharacteristics")
        // Peer-review panel is parallel → isSequential="false".
        assertContains(xml, "isSequential=\"false\"")
        // collectionDescription rides our extension namespace.
        assertContains(xml, "each reviewer on the panel")
        assertContains(xml, "<bpmn:textAnnotation")
        assertContains(xml, "For each reviewer on the panel")
        assertContains(xml, "<bpmn:association")
        assertContains(xml, "sourceRef=\"act-review\"")
    }

    @Test
    fun `multi-instance, annotations, and associations round-trip through render and parse`() {
        val original = miDefinition()

        val parsed = BpmnXmlToDefinitionConverter().parse(converter.render(original).xml)

        val review = parsed.nodes.single { it.id == "act-review" } as BpmnUserTask
        val mi = assertNotNull(review.multiInstance, "expected the review task to carry a multi-instance marker")
        assertEquals(MultiInstanceMode.PARALLEL, mi.mode)
        assertEquals("each reviewer on the panel", mi.collectionDescription)

        val pick = parsed.nodes.single { it.id == "act-pick" } as BpmnServiceTask
        assertEquals(MultiInstanceMode.SEQUENTIAL, assertNotNull(pick.multiInstance).mode)

        assertEquals(original.annotations, parsed.annotations)
        assertEquals(original.associations, parsed.associations)
    }

    @Test
    fun `data objects, stores, and read-write associations round-trip through render and parse`() {
        val original = BpmnDefinition(
            processId = "Process_data",
            processName = "Order validation",
            nodes = listOf(
                BpmnStartEvent("StartEvent_1", "Order received"),
                BpmnServiceTask("act-validate", "Validate order"),
                BpmnEndEvent("end-done", "Order validated"),
            ),
            sequences = listOf(
                BpmnEdge("F1", "StartEvent_1", "act-validate"),
                BpmnEdge("F2", "act-validate", "end-done"),
            ),
            dataObjects = listOf(
                BpmnDataObject("DataObject_order", "Order"),
                BpmnDataObject("DataObject_validated", "Validated order"),
            ),
            dataStores = listOf(BpmnDataStore("DataStore_customer", "Customer database")),
            dataAssociations = listOf(
                BpmnDataAssociation("DA1", "act-validate", "DataObject_order", DataFlowDirection.READ),
                BpmnDataAssociation("DA2", "act-validate", "DataStore_customer", DataFlowDirection.READ),
                BpmnDataAssociation("DA3", "act-validate", "DataObject_validated", DataFlowDirection.WRITE),
            ),
        )

        val parsed = BpmnXmlToDefinitionConverter().parse(converter.render(original).xml)

        assertEquals(original.dataObjects, parsed.dataObjects)
        assertEquals(original.dataStores, parsed.dataStores)
        // Parse groups inputs then outputs, so compare order-independently.
        assertEquals(original.dataAssociations.toSet(), parsed.dataAssociations.toSet())
    }

    @Test
    fun `task without multiInstance emits no loop characteristics`() {
        val definition =
            BpmnDefinition(
                processId = "Process_Plain",
                processName = "Plain",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Start"),
                    BpmnUserTask("act-plain", "Do the thing"),
                    BpmnEndEvent("EndEvent_1", "End"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "act-plain"),
                    BpmnEdge("F2", "act-plain", "EndEvent_1"),
                ),
            )

        val xml = converter.render(definition).xml

        assertFalse(xml.contains("multiInstanceLoopCharacteristics"), "plain task must not emit a loop marker")
        val plain = BpmnXmlToDefinitionConverter().parse(xml).nodes.single { it.id == "act-plain" } as BpmnUserTask
        assertNull(plain.multiInstance)
    }

    @Test
    fun `standard-loop task round-trips through render and parse`() {
        val definition =
            BpmnDefinition(
                processId = "Process_Loop",
                processName = "Standard loop",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Start"),
                    BpmnServiceTask(
                        "act-charge",
                        "Charge card",
                        standardLoop = StandardLoopCharacteristics(
                            testBefore = false,
                            loopCondition = "payment not yet successful",
                            loopMaximum = 3,
                        ),
                    ),
                    BpmnEndEvent("EndEvent_1", "End"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "act-charge"),
                    BpmnEdge("F2", "act-charge", "EndEvent_1"),
                ),
            )

        val xml = converter.render(definition).xml
        assertContains(xml, "standardLoopCharacteristics")
        assertContains(xml, "testBefore=\"false\"")

        val parsed =
            BpmnXmlToDefinitionConverter().parse(xml).nodes.single { it.id == "act-charge" } as BpmnServiceTask
        val loop = assertNotNull(parsed.standardLoop, "expected the charge task to carry a standard-loop marker")
        assertFalse(loop.testBefore)
        assertEquals("payment not yet successful", loop.loopCondition)
        assertEquals(3, loop.loopMaximum)
    }

    // A two-task process: a PARALLEL multi-instance user task (peer review) and a SEQUENTIAL
    // multi-instance service task (line-item picking), each linked to a text annotation naming
    // its item set via an association (sourceRef = task, targetRef = annotation).
    private fun miDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_MI",
        processName = "Multi-instance sample",
        nodes =
        listOf(
            BpmnStartEvent("StartEvent_1", "Start"),
            BpmnUserTask(
                "act-review",
                "Review manuscript",
                multiInstance = MultiInstanceLoopCharacteristics(
                    mode = MultiInstanceMode.PARALLEL,
                    collectionDescription = "each reviewer on the panel",
                ),
            ),
            BpmnServiceTask(
                "act-pick",
                "Pick line item",
                multiInstance = MultiInstanceLoopCharacteristics(
                    mode = MultiInstanceMode.SEQUENTIAL,
                    collectionDescription = "each line item on the slip",
                ),
            ),
            BpmnEndEvent("EndEvent_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge("F1", "StartEvent_1", "act-review"),
            BpmnEdge("F2", "act-review", "act-pick"),
            BpmnEdge("F3", "act-pick", "EndEvent_1"),
        ),
        annotations =
        listOf(
            BpmnTextAnnotation("TextAnnotation_review", "For each reviewer on the panel"),
            BpmnTextAnnotation("TextAnnotation_pick", "For each line item on the slip"),
        ),
        associations =
        listOf(
            BpmnAssociation("Association_review", "act-review", "TextAnnotation_review"),
            BpmnAssociation("Association_pick", "act-pick", "TextAnnotation_pick"),
        ),
    )

    @Test
    fun `converter emits parallelGateway for BpmnParallelGateway nodes`() {
        // Three-track parallel fork mirroring the employee-onboarding sample.
        val definition =
            BpmnDefinition(
                processId = "Process_Parallel",
                processName = "Parallel preparation",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Hire confirmed"),
                    BpmnParallelGateway("dec-prep-tracks", "Run preparation tracks"),
                    BpmnUserTask("act-prep-it", "Provision IT"),
                    BpmnUserTask("act-prep-facilities", "Prepare workspace"),
                    BpmnUserTask("act-prep-manager", "Manager briefing"),
                    BpmnParallelGateway("Gateway_join_prep", null),
                    BpmnUserTask("act-orientation", "Orientation"),
                    BpmnEndEvent("EndEvent_1", "Onboarding complete"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "dec-prep-tracks"),
                    BpmnEdge("F2", "dec-prep-tracks", "act-prep-it"),
                    BpmnEdge("F3", "dec-prep-tracks", "act-prep-facilities"),
                    BpmnEdge("F4", "dec-prep-tracks", "act-prep-manager"),
                    BpmnEdge("F5", "act-prep-it", "Gateway_join_prep"),
                    BpmnEdge("F6", "act-prep-facilities", "Gateway_join_prep"),
                    BpmnEdge("F7", "act-prep-manager", "Gateway_join_prep"),
                    BpmnEdge("F8", "Gateway_join_prep", "act-orientation"),
                    BpmnEdge("F9", "act-orientation", "EndEvent_1"),
                ),
            )

        val xml = converter.render(definition).xml

        assertContains(xml, "<parallelGateway id=\"dec-prep-tracks\"")
        assertContains(xml, "<parallelGateway id=\"Gateway_join_prep\"")
        // No condition expressions on any of the parallel-branch flows
        assertFalse(xml.contains("<conditionExpression"), "Parallel branches must not carry conditions")
    }

    @Test
    fun `converter round-trips an event-based gateway routing to intermediate catch events`() {
        val definition =
            BpmnDefinition(
                processId = "Process_EventBased",
                processName = "Await response",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Charge submitted"),
                    BpmnEventBasedGateway("dec-await", "Await response"),
                    BpmnIntermediateCatchEvent(
                        "evt-ok",
                        name = "Confirmation received",
                        eventDefinition = BpmnNoneEventDefinition,
                    ),
                    BpmnIntermediateCatchEvent(
                        "evt-timeout",
                        name = "Timed out",
                        eventDefinition = BpmnNoneEventDefinition,
                    ),
                    BpmnEndEvent("end-ok", "Settled"),
                    BpmnEndEvent("end-timeout", "Abandoned"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "dec-await"),
                    BpmnEdge("F2", "dec-await", "evt-ok"),
                    BpmnEdge("F3", "dec-await", "evt-timeout"),
                    BpmnEdge("F4", "evt-ok", "end-ok"),
                    BpmnEdge("F5", "evt-timeout", "end-timeout"),
                ),
            )

        val xml = converter.render(definition).xml
        assertContains(xml, "<eventBasedGateway id=\"dec-await\"")

        val parsed = BpmnXmlToDefinitionConverter().parse(converter.toXml(definition))
        assertEquals("dec-await", parsed.nodes.filterIsInstance<BpmnEventBasedGateway>().single().id)
    }

    @Test
    fun `converter maps nodes sequence flows and bpmndi to bpmn xml`() {
        val definition =
            BpmnDefinition(
                processId = "Process_42",
                processName = "Prepare toast",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Order received"),
                    BpmnServiceTask("Task_1", "Toast bread"),
                    BpmnExclusiveGateway("Gateway_1", "Bread toasted?"),
                    BpmnEndEvent("EndEvent_1", "Toast served"),
                ),
                sequences =
                listOf(
                    BpmnEdge(
                        "Flow_1",
                        "StartEvent_1",
                        "Task_1",
                    ),
                    BpmnEdge(
                        "Flow_2",
                        "Task_1",
                        "Gateway_1",
                    ),
                    BpmnEdge(
                        "Flow_3",
                        "Gateway_1",
                        "EndEvent_1",
                        name = "Yes",
                        conditionExpression = "toastReady",
                    ),
                ),
            )

        val rendered = converter.render(definition)
        val xml = rendered.xml

        assertContains(xml, "<process")
        assertContains(xml, "<startEvent id=\"StartEvent_1\"")
        assertContains(xml, "<serviceTask id=\"Task_1\"")
        assertContains(xml, "<exclusiveGateway id=\"Gateway_1\"")
        assertContains(xml, "<endEvent id=\"EndEvent_1\"")
        assertContains(xml, "<sequenceFlow id=\"Flow_3\" name=\"Yes\"")
        assertContains(xml, "<conditionExpression")
        assertContains(xml, "toastReady")
        assertEquals("Process_42", rendered.elementIndex.processId)
        assertEquals("nodes[id=Gateway_1]", rendered.elementIndex.objectRefForElementId("Gateway_1"))
        assertEquals("sequences[id=Flow_3]", rendered.elementIndex.objectRefForElementId("Flow_3"))
    }

    @Test
    fun `converter preserves intermediate message signal and escalation throw event definitions semantically`() {
        val definition =
            BpmnDefinition(
                processId = "Process_Throws",
                processName = "Intermediate throws",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Start"),
                    BpmnIntermediateThrowEvent(
                        "Throw_message",
                        "Notify invoice ready",
                        BpmnMessageEventDefinition("Message_InvoiceReady"),
                    ),
                    BpmnIntermediateThrowEvent(
                        "Throw_signal",
                        "Broadcast stock change",
                        BpmnSignalEventDefinition("Signal_StockChanged"),
                    ),
                    BpmnIntermediateThrowEvent(
                        "Throw_escalation",
                        "Escalate overdue approval",
                        BpmnEscalationEventDefinition("Escalation_ApprovalOverdue"),
                    ),
                    BpmnEndEvent("EndEvent_1", "Done"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "Throw_message"),
                    BpmnEdge("F2", "Throw_message", "Throw_signal"),
                    BpmnEdge("F3", "Throw_signal", "Throw_escalation"),
                    BpmnEdge("F4", "Throw_escalation", "EndEvent_1"),
                ),
                messages = listOf(BpmnMessageRef("Message_InvoiceReady", "invoice ready")),
                signals = listOf(BpmnSignalRef("Signal_StockChanged", "stock changed")),
                escalations = listOf(BpmnEscalationRef("Escalation_ApprovalOverdue", "APPROVAL_OVERDUE")),
            )

        val parsed = BpmnXmlToDefinitionConverter().parse(converter.toXml(definition))

        assertEquals(definition.nodes.associateBy { it.id }, parsed.nodes.associateBy { it.id })
        assertEquals(definition.sequences.associateBy { it.id }, parsed.sequences.associateBy { it.id })
        assertEquals(definition.messages, parsed.messages)
        assertEquals(definition.signals, parsed.signals)
        assertEquals(definition.escalations, parsed.escalations)
    }

    @Test
    fun `generated xml emits no BPMNDI elements or namespaces`() {
        val definition =
            BpmnDefinition(
                processId = "test_process",
                processName = "Test Process",
                nodes =
                listOf(
                    BpmnStartEvent("start", "Start"),
                ),
                sequences = emptyList(),
            )

        val xml = converter.toXml(definition)
        assertFalse(xml.contains("bpmndi:"), "output must not contain bpmndi: elements or namespace")
        assertFalse(xml.contains("BPMNDiagram"), "output must not contain BPMNDiagram element")
        assertFalse(xml.contains("dc:Bounds"), "output must not contain dc:Bounds element")
        assertFalse(xml.contains("di:waypoint"), "output must not contain di:waypoint element")
    }

    @Test
    fun `converter renders timer message and signal start event definitions`() {
        val timerDefinition =
            minimalDefinition(
                start =
                BpmnStartEvent(
                    "StartEvent_timer",
                    "Every morning",
                    eventDefinition = BpmnTimerEventDefinition(BpmnTimerKind.CYCLE, "R/PT24H"),
                ),
            )
        val messageDefinition =
            minimalDefinition(
                start =
                BpmnStartEvent(
                    "StartEvent_message",
                    "Order received",
                    eventDefinition = BpmnMessageEventDefinition("Message_OrderReceived"),
                ),
                messages = listOf(BpmnMessageRef("Message_OrderReceived", "Order received")),
            )
        val signalDefinition =
            minimalDefinition(
                start =
                BpmnStartEvent(
                    "StartEvent_signal",
                    "Incident broadcast",
                    eventDefinition = BpmnSignalEventDefinition("Signal_Incident"),
                ),
                signals = listOf(BpmnSignalRef("Signal_Incident", "Incident broadcast")),
            )

        assertContains(converter.toXml(timerDefinition), "timerEventDefinition")
        assertContains(converter.toXml(timerDefinition), "<bpmn:timeCycle>R/PT24H</bpmn:timeCycle>")
        assertContains(converter.toXml(messageDefinition), "<bpmn:message id=\"Message_OrderReceived\" name=\"Order received\"")
        assertContains(converter.toXml(messageDefinition), "messageEventDefinition messageRef=\"Message_OrderReceived\"")
        assertContains(converter.toXml(signalDefinition), "<bpmn:signal id=\"Signal_Incident\" name=\"Incident broadcast\"")
        assertContains(converter.toXml(signalDefinition), "signalEventDefinition signalRef=\"Signal_Incident\"")
    }

    @Test
    fun `converter writes gateway default attribute when edge isDefault is true`() {
        val definition = creditTierDefinition()

        val xml = converter.toXml(definition)

        // Camunda emits attributes alphabetically — `default` precedes `id` on the gateway.
        assertContains(xml, "<exclusiveGateway default=\"Flow_manual\" id=\"Gateway_1\"")
        assertFalse(
            xml.contains("<sequenceFlow id=\"Flow_manual\"[^>]*>\\s*<conditionExpression".toRegex()),
            "Default flow must not have an inline condition expression",
        )
    }

    @Test
    fun `converter rejects isDefault on non-gateway source`() {
        val definition =
            BpmnDefinition(
                processId = "Process_bad",
                processName = "Bad default",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Start"),
                    BpmnUserTask("Task_1", "Do thing"),
                    BpmnEndEvent("EndEvent_1", "End"),
                ),
                sequences =
                listOf(
                    BpmnEdge("Flow_1", "StartEvent_1", "Task_1"),
                    BpmnEdge("Flow_2", "Task_1", "EndEvent_1", isDefault = true),
                ),
            )

        val ex = kotlin.runCatching { converter.toXml(definition) }.exceptionOrNull()
        require(ex != null) { "expected render to fail for isDefault on non-gateway source" }
        assertContains(
            ex.message ?: "",
            "isDefault is only supported on exclusive- or inclusive-gateway sources",
        )
    }

    @Test
    fun `converter omits name attribute for unnamed converging gateway`() {
        val definition =
            BpmnDefinition(
                processId = "Process_1",
                processName = "Merge decisions",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Request received"),
                    BpmnUserTask("Task_1", "Approve request"),
                    BpmnUserTask("Task_2", "Reject request"),
                    BpmnExclusiveGateway("Gateway_1", null),
                    BpmnEndEvent("EndEvent_1", "Request completed"),
                ),
                sequences =
                listOf(
                    BpmnEdge(
                        "Flow_1",
                        "StartEvent_1",
                        "Task_1",
                    ),
                    BpmnEdge(
                        "Flow_2",
                        "StartEvent_1",
                        "Task_2",
                    ),
                    BpmnEdge(
                        "Flow_3",
                        "Task_1",
                        "Gateway_1",
                    ),
                    BpmnEdge(
                        "Flow_4",
                        "Task_2",
                        "Gateway_1",
                    ),
                    BpmnEdge(
                        "Flow_5",
                        "Gateway_1",
                        "EndEvent_1",
                    ),
                ),
            )

        val xml = converter.toXml(definition)

        assertContains(xml, "<exclusiveGateway id=\"Gateway_1\"")
        assertFalse(xml.contains("<exclusiveGateway id=\"Gateway_1\" name="))
        assertContains(xml, "<userTask id=\"Task_1\" name=\"Approve request\"")
    }

    @Test
    fun `converter renders messageRef on send and receive tasks and decisionRef on business-rule tasks`() {
        val definition = mortgageProcessingDefinition()

        val xml = converter.toXml(definition)

        // Each task subtype emits the matching BPMN element with id intact.
        assertContains(xml, "<scriptTask id=\"act-normalise\"")
        assertContains(xml, "<businessRuleTask")
        assertContains(xml, "<sendTask")
        assertContains(xml, "<receiveTask")
        assertContains(xml, "<manualTask id=\"act-inspect\"")
        // messageRef is a BPMN spec attribute on send/receive task elements.
        assertContains(xml, "messageRef=\"Message_Decline\"")
        assertContains(xml, "messageRef=\"Message_Ack\"")
        // decisionRef is qualified in the bpmner extension namespace.
        assertContains(xml, "bpmner:decisionRef=\"credit-policy\"")
        assertContains(xml, "xmlns:bpmner=\"https://groknull.dev/bpmner/ext\"")
    }

    @Test
    fun `generator emits semantic-only XML regardless of diagramCount`() {
        // BpmnDefinition.diagramCount is a parser-side counter for input documents that
        // carried BPMNDI elements. The generator emits semantic-only XML; this contract
        // ensures no future change starts re-emitting BPMNDI from the count.
        val definition = BpmnDefinition(
            processId = "p1",
            processName = "Round-trip safety",
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnEndEvent("e", "End"),
            ),
            sequences = listOf(BpmnEdge("f", "s", "e")),
            diagramCount = 3,
        )

        val xml = converter.toXml(definition)
        assertFalse(xml.contains("bpmndi:"), "generator must not emit BPMNDI elements: $xml")
        assertFalse(xml.contains("BPMNDiagram"), "generator must not emit BPMNDiagram references: $xml")
    }

    @Test
    fun `generator fails loudly when BpmnUnrecognizedNode reaches it`() {
        // BpmnUnrecognizedNode carries no Camunda model class and cannot round-trip. The
        // generator is the last line of defense: any pipeline that lets one through is a
        // contract bug, so BpmnModelFactory errors instead of silently dropping.
        val definition = BpmnDefinition(
            processId = "p1",
            processName = "Unrecognized contract",
            nodes = listOf(
                BpmnStartEvent("s", "Start"),
                BpmnUnrecognizedNode(id = "tx1", name = null, bpmnType = "bpmn:Transaction"),
                BpmnEndEvent("e", "End"),
            ),
            sequences = listOf(BpmnEdge("f", "s", "e")),
        )

        assertFailsWith<IllegalStateException> { converter.toXml(definition) }
    }

    private fun minimalDefinition(
        start: BpmnStartEvent,
        messages: List<BpmnMessageRef> = emptyList(),
        signals: List<BpmnSignalRef> = emptyList(),
    ) = BpmnDefinition(
        processId = "Process_events",
        processName = "Event starts",
        nodes =
        listOf(
            start,
            BpmnEndEvent("EndEvent_1", "Done"),
        ),
        sequences = listOf(BpmnEdge("Flow_1", start.id, "EndEvent_1")),
        messages = messages,
        signals = signals,
    )
}
