/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnServiceTask
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnUserTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BpmnXmlToDefinitionConverterTest {
    private val forward = BpmnDefinitionToXmlConverter()
    private val reverse = BpmnXmlToDefinitionConverter()

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
    fun `parse rejects xml containing bpmndi elements`() {
        val xmlWithDi =
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
              <bpmndi:BPMNDiagram id="d">
                <bpmndi:BPMNPlane id="plane" bpmnElement="p1">
                  <bpmndi:BPMNShape id="s_di" bpmnElement="s">
                    <dc:Bounds x="0" y="0" width="36" height="36"/>
                  </bpmndi:BPMNShape>
                </bpmndi:BPMNPlane>
              </bpmndi:BPMNDiagram>
            </definitions>
            """.trimIndent()

        val err =
            assertFailsWith<IllegalArgumentException> {
                reverse.parse(xmlWithDi)
            }
        assertTrue(
            err.message!!.contains("BPMNDI input rejected"),
            "rejection message should explain the strict-parse rule",
        )
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

    private fun linearDefinition() =
        BpmnDefinition(
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

    private fun branchingDefinition() =
        BpmnDefinition(
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
}
