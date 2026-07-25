/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AD-622-17 probe (622-3 §2.3): `miwg-c2-four-pools.bpmn`'s `Boundary_fulfil_error` →
 * `SubProcess_fulfil` is the corpus's only boundary-event-on-a-compound-host case nested inside a
 * hierarchical (`INCLUDE_CHILDREN`) collaboration run. Not enrolled in [LAYOUT_CORPUS_FIXTURES] or
 * given a golden — 622-4's gate, per §2.3.
 *
 * Findings (recorded, not fixed here):
 *
 * 1. The SOUTH port survives hierarchical layout with a real edge attached (AD-622-08): it keeps
 *    [PortSide.SOUTH] and its resolved position stays on the host's bottom edge relative to the
 *    host — `NorthSouthPortPreprocessor` has a real edge to engage on now that unit D wires the
 *    port to its handler.
 * 2. The compound host's final BPMN-DI position now lands **inside** its own participant's band —
 *    unlike before unit D, when the deleted `HandlerComponentAlignment`/`ExceptionEdgeRoutes`
 *    passes' shape mutations corrupted the later band-placement pass's containment for this
 *    fixture. That containment violation is gone as a side effect of unit D's deletions, not
 *    because AD-622-05 (pool stacking beyond two participants, still 622-4's gate) has landed —
 *    this fixture stays un-enrolled and un-goldened per §2.3. Recorded for 622-4 (AD-622-13).
 */
class CompoundHostBoundaryProbeTest {

    @Test
    fun `boundary port on a compound host survives hierarchical layout`() {
        val xml = load("layout-fixtures/miwg-c2-four-pools.bpmn")
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        ElkBpmnLayouter().registerElkLayoutAlgorithm()
        val skeleton = BpmnToElkMapper.map(model)
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())

        val hostNode = skeleton.nodeMap["SubProcess_fulfil"] ?: error("host node missing from skeleton")
        val port = skeleton.portMap["Boundary_fulfil_error"] ?: error("boundary port missing from skeleton")

        // Finding 1: the port keeps its declared SOUTH side and sits on the host's bottom edge,
        // relative to the host, after hierarchical layout resolves the host's own size.
        assertEquals(PortSide.SOUTH, port.getProperty(CoreOptions.PORT_SIDE), "port must stay SOUTH")
        assertTrue(
            port.y >= hostNode.height - PORT_EDGE_TOLERANCE,
            "port.y=${port.y} should sit at/near the host's bottom edge (height=${hostNode.height})",
        )

        // Finding 2: post unit-D, the compound host's final DI position is contained within its
        // participant's band once the full production pipeline runs — recorded for 622-4
        // (AD-622-13), not a claim that AD-622-05's pool-stacking gate itself has landed.
        val output = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(output)
        val hostBounds = LayoutDiInspector.shapeBounds(doc, "SubProcess_fulfil")
        val participantBounds = LayoutDiInspector.shapeBounds(doc, "Participant_retailer")
        val hostWithinParticipantBand = hostBounds["y"]!! >= participantBounds["y"]!! &&
            hostBounds["y"]!! + hostBounds["height"]!! <= participantBounds["y"]!! + participantBounds["height"]!!
        assertTrue(
            hostWithinParticipantBand,
            "host (DI y=${hostBounds["y"]}) should now sit inside its participant band " +
                "y=[${participantBounds["y"]}, ${participantBounds["y"]!! + participantBounds["height"]!!}] " +
                "— if this regresses, re-check unit D's deletions against this fixture.",
        )
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()?.readText() ?: error("resource not found: $resource")

    companion object {
        private const val PORT_EDGE_TOLERANCE = 1.0
    }
}
