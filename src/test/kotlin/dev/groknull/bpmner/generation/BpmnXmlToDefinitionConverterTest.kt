/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnErrorRef
import dev.groknull.bpmner.core.BpmnEscalationRef
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnMessageRef
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnReceiveTask
import dev.groknull.bpmner.core.BpmnScriptTask
import dev.groknull.bpmner.core.BpmnSendTask
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnSignalEventDefinition
import dev.groknull.bpmner.core.BpmnSignalRef
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTimerEventDefinition
import dev.groknull.bpmner.core.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Suppress("TooManyFunctions") // test class — each @Test method is one function
class BpmnXmlToDefinitionConverterTest {
    private val forward = BpmnDefinitionToXmlConverter()
    private val reverse = BpmnXmlToDefinitionConverter()

    @Test
    @Suppress("LongMethod") // fixture builds + round-trip assertions stay cohesive
    fun `new task subtypes round-trip with payload fields preserved`() {
        // Single fixture exercises all 5 new task kinds + their payload fields end-to-end.
        val original =
            BpmnDefinition(
                processId = "Process_mortgage_rt",
                processName = "Mortgage processing",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Application submitted"),
                    BpmnScriptTask("act-normalise", "Normalise address"),
                    BpmnBusinessRuleTask("act-credit", "Evaluate credit policy", decisionRef = "credit-policy"),
                    BpmnSendTask("act-decline", "Send decline notification", messageRef = "Message_Decline"),
                    BpmnReceiveTask(
                        "act-await-ack",
                        "Customer acknowledgement received",
                        messageRef = "Message_Ack",
                    ),
                    BpmnManualTask("act-inspect", "Inspect property"),
                    BpmnEndEvent("EndEvent_1", "Application completed"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "act-normalise"),
                    BpmnEdge("F2", "act-normalise", "act-credit"),
                    BpmnEdge("F3", "act-credit", "act-decline"),
                    BpmnEdge("F4", "act-decline", "act-await-ack"),
                    BpmnEdge("F5", "act-await-ack", "act-inspect"),
                    BpmnEdge("F6", "act-inspect", "EndEvent_1"),
                ),
                messages =
                listOf(
                    BpmnMessageRef("Message_Decline", "Decline notification"),
                    BpmnMessageRef("Message_Ack", "Customer acknowledgement"),
                ),
            )

        val xml = forward.toXml(original)
        val parsed = reverse.parse(xml)

        val script = parsed.nodes.single { it.id == "act-normalise" }
        assertIs<BpmnScriptTask>(script)
        val rule = parsed.nodes.single { it.id == "act-credit" }
        assertIs<BpmnBusinessRuleTask>(rule)
        assertEquals("credit-policy", rule.decisionRef)
        val send = parsed.nodes.single { it.id == "act-decline" }
        assertIs<BpmnSendTask>(send)
        assertEquals("Message_Decline", send.messageRef)
        val receive = parsed.nodes.single { it.id == "act-await-ack" }
        assertIs<BpmnReceiveTask>(receive)
        assertEquals("Message_Ack", receive.messageRef)
        val manual = parsed.nodes.single { it.id == "act-inspect" }
        assertIs<BpmnManualTask>(manual)
        assertEquals(2, parsed.messages.size)
    }

    @Test
    fun `parallelGateway xml round-trips through both directions`() {
        // Fork and join both end up as BpmnParallelGateway after a full round-trip.
        val original =
            BpmnDefinition(
                processId = "Process_RT",
                processName = "Round-trip parallel",
                nodes =
                listOf(
                    BpmnStartEvent("StartEvent_1", "Start"),
                    BpmnParallelGateway("dec-fork", "Fork"),
                    BpmnUserTask("act-a", "Track A"),
                    BpmnUserTask("act-b", "Track B"),
                    BpmnParallelGateway("Gateway_join", null),
                    BpmnEndEvent("EndEvent_1", "Done"),
                ),
                sequences =
                listOf(
                    BpmnEdge("F1", "StartEvent_1", "dec-fork"),
                    BpmnEdge("F2", "dec-fork", "act-a"),
                    BpmnEdge("F3", "dec-fork", "act-b"),
                    BpmnEdge("F4", "act-a", "Gateway_join"),
                    BpmnEdge("F5", "act-b", "Gateway_join"),
                    BpmnEdge("F6", "Gateway_join", "EndEvent_1"),
                ),
            )

        val xml = forward.render(original).xml
        val parsed = reverse.parse(xml)

        val fork = parsed.nodes.first { it.id == "dec-fork" }
        val join = parsed.nodes.first { it.id == "Gateway_join" }
        assertIs<BpmnParallelGateway>(fork, "fork should round-trip as BpmnParallelGateway")
        assertIs<BpmnParallelGateway>(join, "join should round-trip as BpmnParallelGateway")
    }

    @Test
    fun `parse surfaces bpmndi diagram count on BpmnDefinition`() {
        // The parser counts `<bpmndi:BPMNDiagram>` elements and exposes the count on
        // `BpmnDefinition.diagramCount`. DI content is dropped from the semantic model; the
        // `NoDuplicateDiagrams` rule enforces the one-diagram policy via `CardinalityCheck`.
        val xmlWithTwoDiagrams =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                         xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
                         targetNamespace="http://example.com/bpmn">
              <process id="p1" name="Has DI">
                <startEvent id="s"/>
                <sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                <endEvent id="e"/>
              </process>
              <bpmndi:BPMNDiagram id="d1">
                <bpmndi:BPMNPlane id="plane1" bpmnElement="p1"/>
              </bpmndi:BPMNDiagram>
              <bpmndi:BPMNDiagram id="d2">
                <bpmndi:BPMNPlane id="plane2" bpmnElement="p1"/>
              </bpmndi:BPMNDiagram>
            </definitions>
            """.trimIndent()

        val parsed = reverse.parse(xmlWithTwoDiagrams)
        assertEquals(2, parsed.diagramCount, "parser should surface the BPMNDI diagram count")
        // DI content is still dropped from the semantic model — only the count survives.
        assertEquals(setOf("s", "e"), parsed.nodes.map { it.id }.toSet())
    }

    @Test
    fun `parse rejects xml containing doctype declarations`() {
        val xmlWithDoctype =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE definitions [
              <!ENTITY injected SYSTEM "file:///etc/passwd">
            ]>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="http://example.com/bpmn">
              <process id="p1" name="Doctype">
                <startEvent id="s" name="Start"/>
                <sequenceFlow id="f" sourceRef="s" targetRef="e"/>
                <endEvent id="e" name="End"/>
              </process>
            </definitions>
            """.trimIndent()

        val err =
            assertFailsWith<Exception> {
                reverse.parse(xmlWithDoctype)
            }
        assertContains(err.message.orEmpty(), "DOCTYPE")
    }

    @Test
    fun `parse rejects timer event definition without timer child`() {
        val malformedTimer =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="http://example.com/bpmn">
              <process id="p1" name="Malformed timer">
                <startEvent id="Start_timer" name="Start">
                  <timerEventDefinition/>
                </startEvent>
                <sequenceFlow id="f" sourceRef="Start_timer" targetRef="End_1"/>
                <endEvent id="End_1" name="End"/>
              </process>
            </definitions>
            """.trimIndent()

        val err =
            assertFailsWith<IllegalArgumentException> {
                reverse.parse(malformedTimer)
            }
        assertContains(err.message.orEmpty(), "Malformed timerEventDefinition for event 'Start_timer'")
        assertContains(err.message.orEmpty(), "expected timeDate, timeDuration, or timeCycle child")
    }

    @Test
    fun `parse surfaces blank messageRef faithfully and validator flags the missing attribute`() {
        // A messageEventDefinition without messageRef is malformed XML. The parser captures it
        // exactly (BpmnMessageEventDefinition("")) so the validator can surface the *actual*
        // problem ("missing required attribute") rather than a misleading referential-integrity
        // diagnostic (the previous "messageRef '' does not match any message catalog id").
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         targetNamespace="http://example.com/bpmn">
              <process id="p1" name="Blank messageRef">
                <startEvent id="Start_msg" name="Start">
                  <messageEventDefinition/>
                </startEvent>
                <sequenceFlow id="f" sourceRef="Start_msg" targetRef="End_1"/>
                <endEvent id="End_1" name="End"/>
              </process>
            </definitions>
            """.trimIndent()

        val parsed = reverse.parse(xml)
        val startEvent = parsed.nodes.first { it.id == "Start_msg" } as BpmnStartEvent
        assertEquals(BpmnMessageEventDefinition(""), startEvent.eventDefinition)

        val errors =
            dev.groknull.bpmner.validation
                .BpmnDefinitionValidator()
                .validate(parsed)
        assertContains(
            errors.joinToString("\n"),
            "event Start_msg messageEventDefinition is missing the required messageRef attribute",
        )
    }

    @Test
    fun `parse ignores error and escalation catalog entries with blank codes`() {
        val definition =
            BpmnDefinition(
                processId = "Process_catalogs",
                processName = "Catalogs",
                nodes =
                listOf(
                    BpmnStartEvent("Start_1", "Start"),
                    BpmnEndEvent("End_1", "End"),
                ),
                sequences = listOf(BpmnEdge("Flow_1", "Start_1", "End_1")),
                errors =
                listOf(
                    BpmnErrorRef("Error_good", "ORDER_FAILED", "Order failed"),
                    BpmnErrorRef("Error_blank", "", "Blank"),
                ),
                escalations =
                listOf(
                    BpmnEscalationRef("Escalation_good", "ORDER_DELAYED", "Order delayed"),
                    BpmnEscalationRef("Escalation_blank", " ", "Blank"),
                ),
            )

        val parsed = reverse.parse(forward.toXml(definition))

        assertEquals(listOf(BpmnErrorRef("Error_good", "ORDER_FAILED", "Order failed")), parsed.errors)
        assertEquals(
            listOf(BpmnEscalationRef("Escalation_good", "ORDER_DELAYED", "Order delayed")),
            parsed.escalations,
        )
        assertFalse(parsed.errors.any { it.id == "Error_blank" })
        assertFalse(parsed.escalations.any { it.id == "Escalation_blank" })
    }

    @Test
    fun `parse ignores message and signal catalog entries with blank names`() {
        val definition =
            BpmnDefinition(
                processId = "Process_catalogs",
                processName = "Catalogs",
                nodes =
                listOf(
                    BpmnStartEvent("Start_1", "Start"),
                    BpmnEndEvent("End_1", "End"),
                ),
                sequences = listOf(BpmnEdge("Flow_1", "Start_1", "End_1")),
                messages =
                listOf(
                    BpmnMessageRef("Message_good", "Order received"),
                    BpmnMessageRef("Message_blank", ""),
                ),
                signals =
                listOf(
                    BpmnSignalRef("Signal_good", "Incident broadcast"),
                    BpmnSignalRef("Signal_blank", " "),
                ),
            )

        val parsed = reverse.parse(forward.toXml(definition))

        assertEquals(listOf(BpmnMessageRef("Message_good", "Order received")), parsed.messages)
        assertEquals(listOf(BpmnSignalRef("Signal_good", "Incident broadcast")), parsed.signals)
        assertFalse(parsed.messages.any { it.id == "Message_blank" })
        assertFalse(parsed.signals.any { it.id == "Signal_blank" })
    }

    @Test
    fun `round-trip a simple linear process preserves nodes and sequences`() {
        val original = linearDefinition()

        val parsed = reverse.parse(forward.toXml(original))

        assertProcessShellEqual(original, parsed)
        assertEquals(original.nodes.byId(), parsed.nodes.byId())
        assertEquals(original.sequences.byId(), parsed.sequences.byId())
    }

    @Test
    fun `round-trip a branching process preserves nodes, sequences, names, and conditions`() {
        val original = branchingDefinition()

        val parsed = reverse.parse(forward.toXml(original))

        assertProcessShellEqual(original, parsed)
        assertEquals(original.nodes.byId(), parsed.nodes.byId())
        assertEquals(original.sequences.byId(), parsed.sequences.byId())
    }

    @Test
    fun `round-trip preserves timer message and signal start event definitions`() {
        val timer =
            eventStartDefinition(
                BpmnStartEvent(
                    "Start_timer",
                    "Every morning",
                    eventDefinition = BpmnTimerEventDefinition(BpmnTimerKind.CYCLE, "R/PT24H"),
                ),
            )
        val message =
            eventStartDefinition(
                BpmnStartEvent(
                    "Start_message",
                    "Order received",
                    eventDefinition = BpmnMessageEventDefinition("Message_OrderReceived"),
                ),
                messages = listOf(BpmnMessageRef("Message_OrderReceived", "Order received")),
            )
        val signal =
            eventStartDefinition(
                BpmnStartEvent(
                    "Start_signal",
                    "Incident broadcast",
                    eventDefinition = BpmnSignalEventDefinition("Signal_Incident"),
                ),
                signals = listOf(BpmnSignalRef("Signal_Incident", "Incident broadcast")),
            )

        assertEventStartRoundTrip(timer)
        assertEventStartRoundTrip(message)
        assertEventStartRoundTrip(signal)
    }

    @Test
    fun `round-trip preserves isDefault on exclusive-gateway default flow`() {
        val original = creditTierDefinition()

        val parsed = reverse.parse(forward.toXml(original))

        val defaultEdge = parsed.sequences.first { it.id == "Flow_manual" }
        assertTrue(defaultEdge.isDefault, "default flow must survive round-trip")
        assertEquals(null, defaultEdge.conditionExpression)

        val conditionalEdge = parsed.sequences.first { it.id == "Flow_fast" }
        assertEquals(false, conditionalEdge.isDefault, "non-default flow must remain non-default")
        assertEquals("score >= 750", conditionalEdge.conditionExpression)

        assertEquals(original.sequences.byId(), parsed.sequences.byId())
    }

    private fun creditTierDefinition() = BpmnDefinition(
        processId = "Process_credit",
        processName = "Credit-tier routing",
        nodes =
        listOf(
            BpmnStartEvent("StartEvent_1", "Score received"),
            BpmnExclusiveGateway("Gateway_1", "Which credit tier?"),
            BpmnUserTask("Task_fast", "Fast-track underwriting"),
            BpmnUserTask("Task_manual", "Manual review"),
            BpmnEndEvent("EndEvent_1", "Offer generated"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_1", "StartEvent_1", "Gateway_1"),
            BpmnEdge(
                "Flow_fast",
                "Gateway_1",
                "Task_fast",
                conditionExpression = "score >= 750",
            ),
            BpmnEdge("Flow_manual", "Gateway_1", "Task_manual", isDefault = true),
            BpmnEdge("Flow_3", "Task_fast", "EndEvent_1"),
            BpmnEdge("Flow_4", "Task_manual", "EndEvent_1"),
        ),
    )

    private fun assertProcessShellEqual(
        a: BpmnDefinition,
        b: BpmnDefinition,
    ) {
        assertEquals(a.processId, b.processId)
        assertEquals(a.processName, b.processName)
        assertEquals(a.nodes.size, b.nodes.size)
        assertEquals(a.sequences.size, b.sequences.size)
    }

    private fun List<BpmnNode>.byId(): Map<String, BpmnNode> = associateBy { it.id }

    @JvmName("edgesById")
    private fun List<BpmnEdge>.byId(): Map<String, BpmnEdge> = associateBy { it.id }

    private fun linearDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Linear Process",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnUserTask("Task_1", "Do work"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge(
                "Flow_1",
                "Start_1",
                "Task_1",
            ),
            BpmnEdge(
                "Flow_2",
                "Task_1",
                "End_1",
            ),
        ),
    )

    private fun branchingDefinition() = BpmnDefinition(
        processId = "Process_2",
        processName = "Branching Process",
        nodes =
        listOf(
            BpmnStartEvent("Start_1", "Start"),
            BpmnExclusiveGateway(
                "Gateway_1",
                "Is valid?",
            ),
            BpmnUserTask("Task_1", "Approve"),
            BpmnServiceTask("Task_2", "Reject"),
            BpmnEndEvent("End_1", "End"),
        ),
        sequences =
        listOf(
            BpmnEdge(
                "Flow_1",
                "Start_1",
                "Gateway_1",
            ),
            BpmnEdge(
                "Flow_2",
                "Gateway_1",
                "Task_1",
                name = "Yes",
            ),
            BpmnEdge(
                "Flow_3",
                "Gateway_1",
                "Task_2",
                name = "No",
                conditionExpression = "\${value < 0}",
            ),
            BpmnEdge(
                "Flow_4",
                "Task_1",
                "End_1",
            ),
            BpmnEdge(
                "Flow_5",
                "Task_2",
                "End_1",
            ),
        ),
    )

    private fun assertEventStartRoundTrip(original: BpmnDefinition) {
        val parsed = reverse.parse(forward.toXml(original))
        assertProcessShellEqual(original, parsed)
        assertEquals(original.nodes.byId(), parsed.nodes.byId())
        assertEquals(original.sequences.byId(), parsed.sequences.byId())
        assertEquals(original.messages, parsed.messages)
        assertEquals(original.signals, parsed.signals)
    }

    private fun eventStartDefinition(
        start: BpmnStartEvent,
        messages: List<BpmnMessageRef> = emptyList(),
        signals: List<BpmnSignalRef> = emptyList(),
    ) = BpmnDefinition(
        processId = "Process_events",
        processName = "Event starts",
        nodes =
        listOf(
            start,
            BpmnEndEvent("End_1", "Done"),
        ),
        sequences = listOf(BpmnEdge("Flow_1", start.id, "End_1")),
        messages = messages,
        signals = signals,
    )
}
