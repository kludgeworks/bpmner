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
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * AD-730-14 cause-3 sweep: measures bend count and two named edges' waypoints on
 * `collab-lanes-loopback` under each candidate [LayeredOptions.NODE_PLACEMENT_STRATEGY]
 * configuration, independently, in seconds rather than the ~2 minutes a full corpus regeneration
 * costs per configuration.
 *
 * Runs the real production pipeline ([BpmnToElkMapper.map] &rarr; layout &rarr;
 * [BpmnPlacementPass.place] &rarr; [ElkToBpmnDiWriter.write]), with one candidate's options applied
 * to the lane-carrying participant compound (`Participant_1`) between mapping and layout — the same
 * seam [BpmnToElkMapper.applyLaneConstraint] uses in production, exercised here with zero production
 * code changes.
 *
 * The baseline row is current production (`INTERACTIVE`, unmodified); `NETWORK_SIMPLEX` is the
 * positive control (per `LaneConstraintSpikeTest`'s own discipline) proving overrides on this
 * compound are read. `SIMPLE`, `LINEAR_SEGMENTS`, and `BRANDES_KOEPF` are AD-730-14's three
 * unexamined node placers; none of them read the imported `y` [NodePlacementStrategy.INTERACTIVE]
 * relies on for banding (AD-730-05/AD-730-11), so lane banding is expected to collapse for all
 * three — that collapse is itself a measurement, not a harness defect.
 *
 * Run via: `bazel run //src/test:sweep_long_edge_dummy_placement`
 */
fun main() {
    LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    val xml = LayoutDiInspector.loadCorpus(object {}.javaClass.classLoader, "collab-lanes-loopback.bpmn")

    val candidates: List<Pair<String, (ElkNode) -> Unit>> = listOf(
        "baseline (INTERACTIVE, current production)" to { _ -> Unit },
        "NETWORK_SIMPLEX (positive control)" to { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
        },
        "SIMPLE" to { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.SIMPLE)
        },
        "LINEAR_SEGMENTS" to { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.LINEAR_SEGMENTS)
        },
        "BRANDES_KOEPF" to { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF)
        },
        "BRANDES_KOEPF + bk.fixedAlignment=BALANCED" to { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF)
            p.setProperty(LayeredOptions.NODE_PLACEMENT_BK_FIXED_ALIGNMENT, FixedAlignment.BALANCED)
        },
        "BRANDES_KOEPF + bk.edgeStraightening=IMPROVE_STRAIGHTNESS" to { p ->
            p.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.BRANDES_KOEPF)
            p.setProperty(
                LayeredOptions.NODE_PLACEMENT_BK_EDGE_STRAIGHTENING,
                EdgeStraighteningStrategy.IMPROVE_STRAIGHTNESS,
            )
        },
    )

    println("%-58s %6s  %-6s  %-10s  %s".format("configuration", "bends", "banded", "edge", "waypoints"))
    for ((label, configure) in candidates) {
        val doc = layoutWith(xml, "Participant_1", configure)
        val bends = countBends(extractEdges(doc))
        // Lane_pickers (Task_pick) must sit strictly above Lane_packers (Task_pack) for banding to
        // survive AD-730-14's first measurement goal (only NodePlacementStrategy.INTERACTIVE honours
        // the imported y this depends on, per AD-730-05's verified mechanics).
        val pickY = LayoutDiInspector.shapeBounds(doc, "Task_pick").getValue("y")
        val packY = LayoutDiInspector.shapeBounds(doc, "Task_pack").getValue("y")
        val banded = pickY < packY
        val checkWaypoints = LayoutDiInspector.edgeWaypoints(doc, "Flow_check")
        val okWaypoints = LayoutDiInspector.edgeWaypoints(doc, "Flow_ok")
        println("%-58s %6d  %-6s  %-10s  %s".format(label, bends, banded, "Flow_check", checkWaypoints))
        println("%-58s %6s  %-6s  %-10s  %s".format("", "", "", "Flow_ok", okWaypoints))
    }
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
