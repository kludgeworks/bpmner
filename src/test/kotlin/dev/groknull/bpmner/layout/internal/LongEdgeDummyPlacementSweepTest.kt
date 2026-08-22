/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.eclipse.elk.alg.layered.options.EdgeStraighteningStrategy
import org.eclipse.elk.alg.layered.options.FixedAlignment
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertTrue

/**
 * AD-730-14 cause-3 sweep, pinned as a regression guard (AD-730-16). Every alternative to
 * `NodePlacementStrategy.INTERACTIVE` was measured to destroy `collab-lanes-loopback`'s lane
 * disjointness outright — `Lane_pickers`/`Lane_packers` render at identical, fully overlapping
 * bounds, not merely closer together. Each test below pins one measured configuration so a future
 * session proposing it again gets a failing assertion instead of re-running the corpus, matching
 * `LaneConstraintSpikeTest`'s established pattern for pinning a measured negative result (e.g. its
 * "Candidate C group model order cannot separate lane members of a serial chain").
 *
 * Production's own lane disjointness (the positive case) is already covered by
 * `ElkGoldenLayoutTest`'s gate-8 test on all three lane fixtures; this class does not repeat it.
 *
 * Runs the real production pipeline ([BpmnToElkMapper.map] &rarr; layout &rarr;
 * [BpmnPlacementPass.place] &rarr; [ElkToBpmnDiWriter.write]), with each configuration applied to
 * the lane-carrying participant compound (`Participant_1`) between mapping and layout — the same
 * seam [BpmnToElkMapper.applyLaneConstraint] uses in production, exercised here with zero production
 * code changes.
 */
class LongEdgeDummyPlacementSweepTest {

    init {
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    }

    @Test
    fun `NETWORK_SIMPLEX destroys collab-lanes-loopback lane disjointness`() {
        assertLaneOverlap("NETWORK_SIMPLEX (positive control)") { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
        }
    }

    @Test
    fun `SIMPLE destroys collab-lanes-loopback lane disjointness`() {
        assertLaneOverlap("SIMPLE") { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.SIMPLE)
        }
    }

    @Test
    fun `LINEAR_SEGMENTS destroys collab-lanes-loopback lane disjointness`() {
        assertLaneOverlap("LINEAR_SEGMENTS") { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.LINEAR_SEGMENTS)
        }
    }

    @Test
    fun `BRANDES_KOEPF destroys collab-lanes-loopback lane disjointness`() {
        assertLaneOverlap("BRANDES_KOEPF") { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF)
        }
    }

    @Test
    fun `BRANDES_KOEPF with bk fixedAlignment=BALANCED destroys collab-lanes-loopback lane disjointness`() {
        assertLaneOverlap("BRANDES_KOEPF + bk.fixedAlignment=BALANCED") { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF)
            p.setProperty(LayeredOptions.NODE_PLACEMENT_BK_FIXED_ALIGNMENT, FixedAlignment.BALANCED)
        }
    }

    @Test
    fun `BRANDES_KOEPF with bk edgeStraightening=IMPROVE_STRAIGHTNESS destroys collab-lanes-loopback lane disjointness`() {
        assertLaneOverlap("BRANDES_KOEPF + bk.edgeStraightening=IMPROVE_STRAIGHTNESS") { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF)
            p.setProperty(
                LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING,
                EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS,
            )
        }
    }

    /**
     * Asserts [configure] reproduces AD-730-16's measured overlap. Reuses [overlappingPairs] — the
     * corpus's own gate-8 check — rather than a coarser single-member Y comparison, which was shown
     * to understate the defect (it can read `false` while the two lane rectangles are still fully,
     * identically overlapping).
     */
    private fun assertLaneOverlap(label: String, configure: (ElkNode) -> Unit) {
        val xml = LayoutDiInspector.loadCorpus(javaClass.classLoader, "collab-lanes-loopback.bpmn")
        val doc = layoutWith(xml, "Participant_1", configure)
        val laneRects = extractShapeRects(doc).filter { it.id == "Lane_pickers" || it.id == "Lane_packers" }

        assertTrue(laneRects.size == 2, "[$label] expected both Lane_pickers and Lane_packers shapes to be present")
        assertTrue(
            overlappingPairs(laneRects).isNotEmpty(),
            "[$label] was measured (AD-730-16) to render Lane_pickers/Lane_packers as fully overlapping. " +
                "If this now passes, AD-730-16's closure needs re-examining before reopening the " +
                "node-placer search — see plans/730/ARCHITECTURE.md.",
        )
    }

    /**
     * Runs the real production layout pipeline on [xml], with [configure] applied to the
     * [participantId] compound between mapping and layout.
     */
    private fun layoutWith(xml: String, participantId: String, configure: (ElkNode) -> Unit): Document {
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        MetadataSynthesis.addMissingAnnotations(model)
        val skeleton = BpmnToElkMapper.map(model)
        MetadataSynthesis.reserveTopPadding(model, skeleton.root)
        configure(skeleton.nodeMap.getValue(participantId))
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())
        val placed = BpmnPlacementPass.place(model, skeleton)
        ElkToBpmnDiWriter.write(model, placed)

        val out = ByteArrayOutputStream()
        Bpmn.writeModelToStream(out, model)
        return LayoutDiInspector.parse(out.toString(Charsets.UTF_8))
    }
}
