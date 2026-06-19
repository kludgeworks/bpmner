/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.domain.BpmnDefinition
import dev.groknull.bpmner.domain.BpmnEndEvent
import dev.groknull.bpmner.domain.BpmnIntermediateCatchEvent
import dev.groknull.bpmner.domain.BpmnNode
import dev.groknull.bpmner.domain.BpmnStartEvent
import dev.groknull.bpmner.domain.BpmnUnrecognizedEventDefinition
import dev.groknull.bpmner.domain.BpmnUnrecognizedNode
import dev.groknull.bpmner.domain.BpmnUserTask
import dev.groknull.bpmner.validation.BpmnDiagnosticSeverity
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class BpmnUnrecognizedElementScannerTest {
    @Test
    fun `scan returns empty when no unrecognized elements`() {
        val def = definitionOf(
            BpmnStartEvent("s", "Start"),
            BpmnUserTask("t", "Do thing"),
            BpmnEndEvent("e", "End"),
        )
        assertTrue(BpmnUnrecognizedElementScanner.scan(def).isEmpty())
    }

    @Test
    fun `scan surfaces BpmnUnrecognizedNode on nodes`() {
        val def = definitionOf(
            BpmnStartEvent("s", "Start"),
            BpmnUnrecognizedNode(id = "ch1", bpmnType = "bpmn:Choreography"),
            BpmnEndEvent("e", "End"),
        )
        val findings = BpmnUnrecognizedElementScanner.scan(def)
        assertEquals(1, findings.size)
        val node = findings.single() as UnrecognizedFinding.Node
        assertEquals("ch1", node.id)
        assertEquals("bpmn:Choreography", node.bpmnType)
    }

    @Test
    fun `scan surfaces BpmnUnrecognizedEventDefinition on event nodes`() {
        val def = definitionOf(
            BpmnStartEvent("s", "Start"),
            BpmnIntermediateCatchEvent(
                id = "ic1",
                name = "Wait for compensate",
                eventDefinition = BpmnUnrecognizedEventDefinition(typeName = "bpmn:CompensateEventDefinition"),
            ),
            BpmnEndEvent("e", "End"),
        )
        val findings = BpmnUnrecognizedElementScanner.scan(def)
        assertEquals(1, findings.size)
        val ed = findings.single() as UnrecognizedFinding.EventDefinition
        assertEquals("ic1", ed.eventId)
        assertEquals("bpmn:CompensateEventDefinition", ed.typeName)
    }

    @Test
    fun `scan surfaces both findings when both present`() {
        val def = definitionOf(
            BpmnStartEvent("s", "Start"),
            BpmnUnrecognizedNode(id = "ch1", bpmnType = "bpmn:Choreography"),
            BpmnIntermediateCatchEvent(
                id = "ic1",
                eventDefinition = BpmnUnrecognizedEventDefinition(typeName = "bpmn:CompensateEventDefinition"),
            ),
            BpmnEndEvent("e", "End"),
        )
        val findings = BpmnUnrecognizedElementScanner.scan(def)
        assertEquals(2, findings.size)
        assertTrue(findings.any { it is UnrecognizedFinding.Node && it.id == "ch1" })
        assertTrue(findings.any { it is UnrecognizedFinding.EventDefinition && it.eventId == "ic1" })
    }

    @Test
    fun `Node toDiagnostic carries the typed shape`() {
        val diag = UnrecognizedFinding.Node(id = "ch1", bpmnType = "bpmn:Choreography").toDiagnostic()
        assertEquals(BpmnDiagnosticSource.LINT, diag.source)
        assertEquals(BpmnDiagnosticSeverity.ERROR, diag.severity)
        assertEquals("repair-unsupported-element", diag.rule)
        assertEquals("ch1", diag.elementId)
        assertEquals(RepairKind.UNFIXABLE, diag.kind)
        assertTrue(diag.message.contains("bpmn:Choreography"))
        assertTrue(diag.message.contains("ch1"))
        assertNull(diag.repairScope)
    }

    @Test
    fun `EventDefinition toDiagnostic carries the typed shape`() {
        val diag = UnrecognizedFinding.EventDefinition(
            eventId = "ic1",
            typeName = "bpmn:CompensateEventDefinition",
        ).toDiagnostic()
        assertEquals(BpmnDiagnosticSource.LINT, diag.source)
        assertEquals(BpmnDiagnosticSeverity.ERROR, diag.severity)
        assertEquals("repair-unsupported-element", diag.rule)
        assertEquals("ic1", diag.elementId)
        assertEquals(RepairKind.UNFIXABLE, diag.kind)
        assertTrue(diag.message.contains("bpmn:CompensateEventDefinition"))
        assertTrue(diag.message.contains("ic1"))
        assertNull(diag.repairScope)
    }

    private fun definitionOf(vararg nodes: BpmnNode): BpmnDefinition = BpmnDefinition(
        processId = "Process_1",
        processName = "p",
        nodes = nodes.toList(),
        sequences = emptyList(),
    )
}
