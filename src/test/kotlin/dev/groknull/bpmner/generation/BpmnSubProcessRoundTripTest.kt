/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.api.MultiInstanceMode
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnSubProcess
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.core.MultiInstanceLoopCharacteristics
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * An embedded subprocess survives render → parse → render. The flat model carries every child's
 * `parentRef`; the renderer reconstructs the `<subProcess>` nesting and Camunda's parse (the XSD
 * gate) recovers it. Covers one subprocess, a subprocess nested in a subprocess, and a
 * multi-instance task inside a subprocess (the DOM post-pass edge case).
 */
class BpmnSubProcessRoundTripTest {
    private val converter = BpmnDefinitionToXmlConverter()

    @Test
    fun `embedded subprocess nests its children and round-trips every parentRef`() {
        val original = oneSubProcessDefinition()

        val xml = converter.render(original).xml
        assertContains(xml, "<subProcess id=\"SubProcess_1\"")

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)

        // The subprocess marker survives as a typed node, not the unrecognized fallback.
        val sp = parsed.nodes.single { it.id == "SubProcess_1" }
        assertIs<BpmnSubProcess>(sp)
        assertFalse(sp.triggeredByEvent, "an ordinary embedded subprocess is not event-triggered")

        // Containment is reconstructed: every node and edge recovers the exact scope it was rendered in.
        assertEquals(parentRefById(original.nodes), parentRefById(parsed.nodes))
        assertEquals(parentRefByEdgeId(original.sequences), parentRefByEdgeId(parsed.sequences))
        assertEquals(original.nodes.size, parsed.nodes.size)
    }

    @Test
    fun `subprocess nested inside a subprocess round-trips at depth two`() {
        // Exercises the renderer's parent-before-child container resolution: SubProcess_inner must
        // attach to SubProcess_outer, which must itself attach to the process — at any list order.
        val original = nestedSubProcessDefinition()

        val xml = converter.render(original).xml
        assertContains(xml, "<subProcess id=\"SubProcess_outer\"")
        assertContains(xml, "<subProcess id=\"SubProcess_inner\"")

        val parsed = BpmnXmlToDefinitionConverter().parse(xml)
        assertIs<BpmnSubProcess>(parsed.nodes.single { it.id == "SubProcess_outer" })
        assertIs<BpmnSubProcess>(parsed.nodes.single { it.id == "SubProcess_inner" })
        assertEquals(parentRefById(original.nodes), parentRefById(parsed.nodes))
        assertEquals(parentRefByEdgeId(original.sequences), parentRefByEdgeId(parsed.sequences))
    }

    @Test
    fun `multi-instance task inside a subprocess round-trips its loop marker`() {
        // The multi-instance DOM post-pass scans the whole document, so it must still find a task
        // nested inside a subprocess.
        val original = multiInstanceInSubProcessDefinition()

        val parsed = BpmnXmlToDefinitionConverter().parse(converter.render(original).xml)

        val task = parsed.nodes.single { it.id == "act-mi" }
        assertIs<BpmnUserTask>(task)
        assertEquals("SubProcess_1", task.parentRef, "the MI task stays scoped to its subprocess")
        val mi = assertNotNull(task.multiInstance, "the nested task must keep its multi-instance marker")
        assertEquals(MultiInstanceMode.PARALLEL, mi.mode)
        assertEquals("each shipment line", mi.collectionDescription)
    }

    private fun parentRefById(nodes: List<BpmnNode>): Map<String, String?> = nodes.associate { it.id to it.parentRef }

    private fun parentRefByEdgeId(edges: List<BpmnEdge>): Map<String, String?> = edges.associate { it.id to it.parentRef }

    private fun oneSubProcessDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_sp",
        processName = "One subprocess",
        nodes =
        listOf(
            BpmnStartEvent("StartEvent_top", "Start"),
            BpmnSubProcess("SubProcess_1", "Handle order"),
            BpmnStartEvent("StartEvent_inner", "Begin", parentRef = "SubProcess_1"),
            BpmnUserTask("act-work", "Do work", parentRef = "SubProcess_1"),
            BpmnEndEvent("EndEvent_inner", "Inner done", parentRef = "SubProcess_1"),
            BpmnEndEvent("EndEvent_top", "Done"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_top1", "StartEvent_top", "SubProcess_1"),
            BpmnEdge("Flow_top2", "SubProcess_1", "EndEvent_top"),
            BpmnEdge("Flow_in1", "StartEvent_inner", "act-work", parentRef = "SubProcess_1"),
            BpmnEdge("Flow_in2", "act-work", "EndEvent_inner", parentRef = "SubProcess_1"),
        ),
    )

    private fun nestedSubProcessDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_nested",
        processName = "Nested subprocess",
        nodes =
        listOf(
            BpmnStartEvent("StartEvent_top", "Start"),
            BpmnSubProcess("SubProcess_outer", "Outer"),
            BpmnStartEvent("StartEvent_o", "Outer begin", parentRef = "SubProcess_outer"),
            BpmnSubProcess("SubProcess_inner", "Inner", parentRef = "SubProcess_outer"),
            BpmnStartEvent("StartEvent_i", "Inner begin", parentRef = "SubProcess_inner"),
            BpmnUserTask("act-inner", "Inner work", parentRef = "SubProcess_inner"),
            BpmnEndEvent("EndEvent_i", "Inner done", parentRef = "SubProcess_inner"),
            BpmnEndEvent("EndEvent_o", "Outer done", parentRef = "SubProcess_outer"),
            BpmnEndEvent("EndEvent_top", "Done"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_t1", "StartEvent_top", "SubProcess_outer"),
            BpmnEdge("Flow_t2", "SubProcess_outer", "EndEvent_top"),
            BpmnEdge("Flow_o1", "StartEvent_o", "SubProcess_inner", parentRef = "SubProcess_outer"),
            BpmnEdge("Flow_o2", "SubProcess_inner", "EndEvent_o", parentRef = "SubProcess_outer"),
            BpmnEdge("Flow_i1", "StartEvent_i", "act-inner", parentRef = "SubProcess_inner"),
            BpmnEdge("Flow_i2", "act-inner", "EndEvent_i", parentRef = "SubProcess_inner"),
        ),
    )

    private fun multiInstanceInSubProcessDefinition(): BpmnDefinition = BpmnDefinition(
        processId = "Process_mi_sp",
        processName = "MI inside subprocess",
        nodes =
        listOf(
            BpmnStartEvent("StartEvent_top", "Start"),
            BpmnSubProcess("SubProcess_1", "Ship items"),
            BpmnStartEvent("StartEvent_in", "Begin", parentRef = "SubProcess_1"),
            BpmnUserTask(
                "act-mi",
                "Ship line",
                multiInstance =
                MultiInstanceLoopCharacteristics(
                    mode = MultiInstanceMode.PARALLEL,
                    collectionDescription = "each shipment line",
                ),
                parentRef = "SubProcess_1",
            ),
            BpmnEndEvent("EndEvent_in", "Inner done", parentRef = "SubProcess_1"),
            BpmnEndEvent("EndEvent_top", "Done"),
        ),
        sequences =
        listOf(
            BpmnEdge("Flow_top1", "StartEvent_top", "SubProcess_1"),
            BpmnEdge("Flow_top2", "SubProcess_1", "EndEvent_top"),
            BpmnEdge("Flow_m1", "StartEvent_in", "act-mi", parentRef = "SubProcess_1"),
            BpmnEdge("Flow_m2", "act-mi", "EndEvent_in", parentRef = "SubProcess_1"),
        ),
    )
}
