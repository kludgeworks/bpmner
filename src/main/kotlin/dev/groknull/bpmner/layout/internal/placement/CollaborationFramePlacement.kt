/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_GAP
import dev.groknull.bpmner.layout.internal.BpmnToElkMapper.PARTICIPANT_HEADER_WIDTH
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.SubProcess
import kotlin.math.hypot

/**
 * Projects every collaboration participant from ELK-owned geometry into a single BPMN-declared
 * coordinate frame: a lane-carrying participant's lanes stack within it, two or more white-box
 * participants stack into one shared column, and any black-box participant stacks below the
 * white-box union. Each stacking step is one rigid translation per frame, applied to shapes,
 * waypoints and labels together.
 *
 * Cross-participant message flows are excluded from ELK's graph, so their route and label are
 * synthesized ([MessageFlowSynthesis]) once every participant frame has its final position, and
 * any sequence flow crossing a translated frame boundary is repaired ([SequenceFlowRepair]). A
 * single-participant, lane-less collaboration needs no repositioning and is left untouched.
 */
internal object CollaborationFramePlacement : PlacementProcessor {

    private const val OWNER = "CollaborationFramePlacement"
    private const val LANE_LABEL_WIDTH = PARTICIPANT_HEADER_WIDTH

    override fun process(ctx: PlacementContext) {
        val collaboration = ctx.model.getModelElementsByType(Collaboration::class.java).firstOrNull() ?: return
        val whiteBox = collaboration.participants.filter { it.process != null }
        val blackBox = collaboration.participants.filter { it.process == null }

        whiteBox.forEach { participant -> projectParticipant(participant, ctx) }
        val translations = mutableMapOf<String, Point>()
        if (whiteBox.size >= 2) stackWhiteBoxPools(whiteBox, ctx, translations)
        blackBox.forEach { participant -> projectBlackBox(participant, ctx) }
        if (blackBox.isNotEmpty() && whiteBox.isNotEmpty()) stackBlackBoxBands(whiteBox, blackBox, ctx)

        SequenceFlowRepair.repair(translations, ctx)
        MessageFlowSynthesis.synthesize(collaboration, ctx)
    }

    private fun projectParticipant(participant: Participant, ctx: PlacementContext) {
        val elkBounds = ctx.skeleton.nodeMap[participant.id]?.let(::elkBounds) ?: return
        val lanes = participant.process?.laneSets.orEmpty().flatMap { it.lanes.toList() }
        val bounds = if (lanes.isEmpty()) elkBounds else projectLaneBands(lanes, elkBounds, ctx)
        ctx.shapes[participant.id] = bounds
        if (!participant.name.isNullOrBlank()) {
            ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
        }
    }

    /**
     * Projects each lane's band rectangle over its members' already-settled shapes (AD-730-06):
     * lane membership constrained ELK's own placement before routing ([BpmnToElkMapper]'s
     * `applyLaneConstraint`/`computeLaneBands`), so the members are already correctly ordered,
     * banded and centred — this reads that geometry, it never moves a member.
     *
     * A band is its declared [BpmnToElkMapper.ElkSkeleton.laneBandHeights] height centred on its
     * members' settled shared centreline. Height and centreline therefore come from one authority,
     * which makes each band's midpoint its members' midpoint and makes consecutive bands share a
     * seam exactly — deriving a seam here from settled extents instead is what let the two
     * disagree. Returns the participant rectangle spanning the resulting bands.
     */
    private fun projectLaneBands(
        lanes: List<Lane>,
        participantBounds: Rect,
        ctx: PlacementContext,
    ): Rect {
        val bands = lanes.map { lane ->
            // Only the lane's own declared members carry the band centreline; a subprocess
            // descendant is positioned within its parent compound, not on the lane's centreline.
            val centreline = lane.flowNodeRefs.firstNotNullOf { ctx.shapes[it.id] }.let { it.y + it.h / 2.0 }
            val height = ctx.skeleton.laneBandHeights.getValue(lane.id)
            lane to Rect(
                participantBounds.x + PARTICIPANT_HEADER_WIDTH,
                centreline - height / 2.0,
                participantBounds.w - PARTICIPANT_HEADER_WIDTH,
                height,
            )
        }
        bands.forEach { (lane, band) ->
            ctx.shapes[lane.id] = band
            if (!lane.name.isNullOrBlank()) {
                ctx.labels[lane.id] = Rect(band.x, band.y, LANE_LABEL_WIDTH, band.h)
            }
        }
        val top = bands.minOf { (_, band) -> band.y }
        val bottom = bands.maxOf { (_, band) -> band.y + band.h }
        return Rect(participantBounds.x, top, participantBounds.w, bottom - top)
    }

    /** Every flow-node in [seeds], recursing into subprocess descendants. */
    private fun flowNodeMembers(seeds: Collection<FlowNode>): Set<String> {
        val members = mutableSetOf<String>()
        fun add(node: FlowNode) {
            members.add(node.id)
            if (node is SubProcess) node.flowElements.filterIsInstance<FlowNode>().forEach(::add)
        }
        seeds.forEach(::add)
        return members
    }

    private fun stackWhiteBoxPools(whiteBox: List<Participant>, ctx: PlacementContext, translations: MutableMap<String, Point>) {
        val originalBounds = whiteBox.associateWith { ctx.shapes.getValue(it.id) }
        val bandX = originalBounds.values.minOf { it.x }
        // Each pool's own natural width, not the union of pre-stacking x-ranges: ELK's root
        // Direction.RIGHT layout spreads participants far apart horizontally before this pass
        // translates them into a column, so unioning their x-ranges would inflate every band to
        // the combined side-by-side span instead of the widest pool's actual content width.
        val bandW = originalBounds.values.maxOf { it.w }

        var nextY = originalBounds.getValue(whiteBox.first()).y
        whiteBox.forEach { participant ->
            val shape = originalBounds.getValue(participant)
            val vector = Point(bandX - shape.x, nextY - shape.y)
            ctx.shapes[participant.id] = Rect(bandX, nextY, bandW, shape.h)
            PlacementTranslations.ledgerMove(participant.id, OWNER, ctx)
            if (!participant.name.isNullOrBlank()) {
                ctx.labels[participant.id] = Rect(bandX, nextY, PARTICIPANT_HEADER_WIDTH, shape.h)
            }
            PlacementTranslations.translateAndLedger(
                participantMembers(participant).associateWith { vector },
                OWNER,
                ctx,
            )
            // Compose with any lane vector already recorded for this participant's members.
            participantMembers(participant).forEach { id ->
                translations[id] = translations[id]?.let { Point(it.x + vector.x, it.y + vector.y) } ?: vector
            }
            nextY += shape.h + PARTICIPANT_GAP
        }
    }

    /**
     * Every flow-node this participant owns: top-level flow elements plus subprocess descendants
     * and lanes. Shared with [MessageFlowSynthesis] for mapping a flow endpoint to its participant.
     */
    fun participantMembers(participant: Participant): Set<String> {
        val members = flowNodeMembers(participant.process?.flowElements?.filterIsInstance<FlowNode>().orEmpty()).toMutableSet()
        participant.process?.laneSets.orEmpty().flatMap { it.lanes }.forEach { members.add(it.id) }
        return members
    }

    private fun projectBlackBox(participant: Participant, ctx: PlacementContext) {
        val bounds = ctx.skeleton.nodeMap[participant.id]?.let(::elkBounds) ?: return
        ctx.shapes[participant.id] = bounds
        if (!participant.name.isNullOrBlank()) {
            ctx.labels[participant.id] = Rect(bounds.x, bounds.y, PARTICIPANT_HEADER_WIDTH, bounds.h)
        }
    }

    private fun stackBlackBoxBands(whiteBox: List<Participant>, blackBox: List<Participant>, ctx: PlacementContext) {
        val whiteBounds = whiteBox.mapNotNull { ctx.shapes[it.id] }
        if (whiteBounds.isEmpty()) return
        val left = whiteBounds.minOf { it.x }
        val width = whiteBounds.maxOf { it.x + it.w } - left
        var nextY = whiteBounds.maxOf { it.y + it.h } + PARTICIPANT_GAP
        blackBox.forEach { participant ->
            val previous = ctx.shapes[participant.id] ?: return@forEach
            val band = Rect(left, nextY, width, previous.h)
            ctx.shapes[participant.id] = band
            ctx.labels[participant.id] = Rect(band.x, band.y, PARTICIPANT_HEADER_WIDTH, band.h)
            PlacementTranslations.ledgerMove(participant.id, OWNER, ctx)
            nextY += band.h + PARTICIPANT_GAP
        }
    }

    /**
     * Centres [id]'s label on its current [PlacementContext.edges] route, if it has one and a
     * name. Shared with [SequenceFlowRepair] and [MessageFlowSynthesis] for their re-routed edges.
     */
    fun centerLabelOnRoute(id: String, name: String?, ctx: PlacementContext) {
        if (name.isNullOrBlank()) return
        val route = ctx.edges[id] ?: return
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions(name, BpmnPlacementPass.EDGE_LABEL_WIDTH)
        val mid = pathMidpoint(route)
        ctx.labels[id] = Rect(mid.x - width / 2.0, mid.y - height / 2.0, width, height)
    }

    /**
     * The point halfway along [route] by cumulative segment length (not the midpoint of its first
     * and last waypoints, which for a route with an unequal bend on each side can land off the
     * polyline entirely).
     */
    private fun pathMidpoint(route: List<Point>): Point {
        if (route.size <= 1) return route.first()
        val segments = route.zipWithNext()
        val lengths = segments.map { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        val total = lengths.sum()
        if (total <= 0.0) return route.first()
        var remaining = total / 2.0
        segments.forEachIndexed { i, (a, b) ->
            val len = lengths[i]
            if (remaining <= len) {
                val t = if (len > 0.0) remaining / len else 0.0
                return Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
            remaining -= len
        }
        return route.last()
    }

    private fun elkBounds(node: org.eclipse.elk.graph.ElkNode): Rect {
        val (x, y) = BpmnPlacementPass.absolutePosition(node)
        return Rect(x, y, node.width, node.height)
    }
}
