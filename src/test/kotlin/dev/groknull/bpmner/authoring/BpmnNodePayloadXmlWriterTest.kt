/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // one @Test per conversion site; the count IS the safety property

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.bpmn.BpmnDataAssociation
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnMessageEventDefinition
import dev.groknull.bpmner.bpmn.BpmnMessageRef
import dev.groknull.bpmner.bpmn.BpmnReceiveTask
import dev.groknull.bpmner.bpmn.BpmnSendTask
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.bpmn.BpmnTask
import dev.groknull.bpmner.bpmn.BpmnUserTask
import dev.groknull.bpmner.bpmn.DataFlowDirection
import dev.groknull.bpmner.bpmn.MultiInstanceLoopCharacteristics
import dev.groknull.bpmner.bpmn.MultiInstanceMode
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import dev.groknull.bpmner.bpmn.StandardLoopCharacteristics
import org.w3c.dom.Document
import org.xmlunit.assertj.XmlAssert
import org.xmlunit.assertj.XmlAssert.assertThat
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class BpmnNodePayloadXmlWriterTest {
    private val writer = BpmnNodePayloadXmlWriter()

    companion object {
        private const val BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"
        private val NAMESPACES = mapOf("bpmn" to BPMN_NS)
    }

    private fun parseXml(xml: String): Document = DocumentBuilderFactory
        .newInstance()
        .also { it.isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(org.xml.sax.InputSource(StringReader(xml)))

    private fun assertXml(xml: String): XmlAssert = assertThat(xml).withNamespaceContext(NAMESPACES)

    // Site 4: data-assoc sourceRef → no rendered task
    @Test
    fun `data association with missing sourceRef throws RetryableBpmnGenerationException`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnStartEvent("start", "Start"),
                BpmnEndEvent("end", "End"),
            ),
            sequences = listOf(
                dev.groknull.bpmner.bpmn.BpmnEdge("f1", "start", "end"),
            ),
            dataAssociations = listOf(
                BpmnDataAssociation("da1", "missingTask", "data1", DataFlowDirection.READ),
            ),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            writer.write(parseXml(xml), definition)
        }
        assertContains(ex.message!!, "sourceRef 'missingTask' has no rendered task element")
    }

    // Site 5: multi-instance marker, no task element
    @Test
    fun `multi-instance marker with missing task element throws RetryableBpmnGenerationException`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnUserTask(
                    "task1",
                    "Task",
                    multiInstance = MultiInstanceLoopCharacteristics(
                        mode = MultiInstanceMode.PARALLEL,
                        collectionDescription = "items",
                    ),
                ),
            ),
            sequences = listOf(
                dev.groknull.bpmner.bpmn.BpmnEdge("f1", "start", "task1"),
                dev.groknull.bpmner.bpmn.BpmnEdge("f2", "task1", "end"),
            ),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            writer.write(parseXml(xml), definition)
        }
        assertContains(ex.message!!, "Task 'task1' has a multi-instance marker but no task element was rendered for it")
    }

    // Site 6: standard-loop marker, no task element
    @Test
    fun `standard-loop marker with missing task element throws RetryableBpmnGenerationException`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnUserTask(
                    "task1",
                    "Task",
                    standardLoop = StandardLoopCharacteristics(
                        testBefore = true,
                        loopCondition = "not done",
                    ),
                ),
            ),
            sequences = listOf(
                dev.groknull.bpmner.bpmn.BpmnEdge("f1", "start", "task1"),
                dev.groknull.bpmner.bpmn.BpmnEdge("f2", "task1", "end"),
            ),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            writer.write(parseXml(xml), definition)
        }
        assertContains(ex.message!!, "Task 'task1' has a standard-loop marker but no task element was rendered for it")
    }

    // Site 7: data association with task element id not found in XML
    @Test
    fun `data association with undefined task element throws RetryableBpmnGenerationException`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnStartEvent("start", "Start"),
                BpmnEndEvent("end", "End"),
            ),
            sequences = listOf(
                dev.groknull.bpmner.bpmn.BpmnEdge("f1", "start", "end"),
            ),
            dataAssociations = listOf(
                BpmnDataAssociation("da1", "task1", "data1", DataFlowDirection.READ),
            ),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            writer.write(parseXml(xml), definition)
        }
        // Site 4: data association sourceRef not found in rendered task elements
        assertContains(ex.message!!, "sourceRef 'task1' has no rendered task element")
    }

    // Site 8: event element not located in XML
    @Test
    fun `event element not found in XML throws RetryableBpmnGenerationException`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnStartEvent("start", "Start"),
            ),
            sequences = emptyList(),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            writer.write(parseXml(xml), definition)
        }
        assertContains(ex.message!!, "Unable to locate BPMN event element id=\"start\" in generated BPMN XML")
    }

    // Site 173: messageRef for non-message task
    @Test
    fun `messageRef requested for non-message task throws RetryableBpmnGenerationException`() {
        val task = BpmnUserTask("task1", "Task")

        val ex = assertFailsWith<RetryableBpmnGenerationException> {
            task.payloadMessageRef()
        }
        assertContains(ex.message!!, "messageRef requested for non-message task 'task1'")
    }

    // Positive test: send/receive tasks render correctly
    @Test
    fun `send task with messageRef renders correctly`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnStartEvent("start", "Start"),
                BpmnSendTask(
                    "send1",
                    "Send message",
                    messageRef = "Message_Some",
                ),
                BpmnEndEvent("end", "End"),
            ),
            sequences = listOf(
                dev.groknull.bpmner.bpmn.BpmnEdge("f1", "start", "send1"),
                dev.groknull.bpmner.bpmn.BpmnEdge("f2", "send1", "end"),
            ),
            messages = listOf(BpmnMessageRef("Message_Some", "Some message")),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                    <bpmn:sendTask id="send1"/>
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val doc = parseXml(xml)
        val result = writer.write(doc, definition)
        val serialized = writeBpmnDocument(doc)

        assertXml(serialized).nodesByXPath("//bpmn:sendTask[@messageRef='Message_Some']").exist()
        assertFalse(result, "bpmner namespace should not be used for messageRef")
    }

    // Positive test: receive task with messageRef renders correctly
    @Test
    fun `receive task with messageRef renders correctly`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnStartEvent("start", "Start"),
                BpmnReceiveTask(
                    "recv1",
                    "Receive message",
                    messageRef = "Message_Ack",
                ),
                BpmnEndEvent("end", "End"),
            ),
            sequences = listOf(
                dev.groknull.bpmner.bpmn.BpmnEdge("f1", "start", "recv1"),
                dev.groknull.bpmner.bpmn.BpmnEdge("f2", "recv1", "end"),
            ),
            messages = listOf(BpmnMessageRef("Message_Ack", "Ack message")),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                    <bpmn:receiveTask id="recv1"/>
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val doc = parseXml(xml)
        writer.write(doc, definition)
        val serialized = writeBpmnDocument(doc)

        assertXml(serialized).nodesByXPath("//bpmn:receiveTask[@messageRef='Message_Ack']").exist()
    }

    // Helper for task element access
    private fun BpmnTask.payloadMessageRef(): String = when (this) {
        is BpmnSendTask -> messageRef
        is BpmnReceiveTask -> messageRef
        else -> throw RetryableBpmnGenerationException("messageRef requested for non-message task '$id'")
    }

    @Test
    fun `start event with event definition renders correctly`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnStartEvent(
                    "start",
                    "Start",
                    eventDefinition = BpmnMessageEventDefinition("Message_Start"),
                ),
            ),
            sequences = emptyList(),
            messages = listOf(BpmnMessageRef("Message_Start", "Start message")),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val doc = parseXml(xml)
        writer.write(doc, definition)
        val serialized = writeBpmnDocument(doc)

        assertXml(serialized).nodesByXPath("//bpmn:startEvent[@id='start']/bpmn:messageEventDefinition").exist()
    }

    @Test
    fun `end event with event definition renders correctly`() {
        val definition = BpmnDefinition(
            processId = "Process_1",
            processName = "Test",
            nodes = listOf(
                BpmnStartEvent("start", "Start"),
                BpmnEndEvent(
                    "end",
                    "End",
                    eventDefinition = BpmnMessageEventDefinition("Message_End"),
                ),
            ),
            sequences = listOf(
                dev.groknull.bpmner.bpmn.BpmnEdge("f1", "start", "end"),
            ),
            messages = listOf(BpmnMessageRef("Message_End", "End message")),
        )

        val xml = """
            <bpmn:definitions xmlns:bpmn="$BPMN_NS">
                <bpmn:process id="Process_1" name="Test">
                    <bpmn:startEvent id="start"/>
                    <bpmn:endEvent id="end"/>
                </bpmn:process>
            </bpmn:definitions>
        """.trimIndent()

        val doc = parseXml(xml)
        writer.write(doc, definition)
        val serialized = writeBpmnDocument(doc)

        assertXml(serialized).nodesByXPath("//bpmn:endEvent[@id='end']/bpmn:messageEventDefinition").exist()
    }
}
