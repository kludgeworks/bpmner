/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.api.BpmnTimerKind
import dev.groknull.bpmner.core.BpmnBusinessRuleTask
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnManualTask
import dev.groknull.bpmner.core.BpmnMessageEventDefinition
import dev.groknull.bpmner.core.BpmnMessageRef
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
import kotlin.test.assertFalse

class BpmnDefinitionToXmlConverterTest {
    private val converter = BpmnDefinitionToXmlConverter()

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
        val definition =
            BpmnDefinition(
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
            "isDefault is only supported on exclusive-gateway sources",
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
        val definition =
            BpmnDefinition(
                processId = "Process_mortgage",
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
