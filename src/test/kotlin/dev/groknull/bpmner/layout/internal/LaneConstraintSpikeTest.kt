/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.eclipse.elk.alg.layered.options.GroupOrderStrategy
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.core.math.KVector
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.PortAlignment
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AD-730-06 decision spike: which stock `elk.layered` mechanism, if any, can express BPMN lane
 * bands as a routing input (declared before phase-5 edge routing) rather than a post-layout
 * translation. Every graph below is wrapped exactly as production maps a lane-carrying
 * participant ([BpmnToElkMapper.applyParticipantProfile]): an outer root containing one compound
 * with `HIERARCHY_HANDLING = INCLUDE_CHILDREN`, flow nodes as its direct children, no lane
 * compound — so `GraphConfigurator`'s hierarchical branches (greedy switch, long-edge handling)
 * exercise the same code path production uses, not a simplified non-hierarchical stand-in.
 *
 * Topology has two split/rejoin pairs in series (two separate cross-lane crossings at different
 * absolute layers) plus one layer-spanning edge, so a candidate's banding claim can be measured
 * globally across the whole diagram and against a long-edge dummy node, not just within one layer.
 *
 * Candidate B (AD-730-06, withdrawn by AD-730-07) has no test below because it is not
 * executable: `javap` on the pinned `org.eclipse.elk.alg.layered-0.12.0.jar` confirms
 * `ElkLayered.hierarchicalLayout` is `private` and `GraphConfigurator` carries no access
 * modifier (package-private), so no public composition of `AlgorithmAssembler`/`LayeredPhases`
 * can splice a lane-ordering processor into a hierarchical run. There is nothing to assert
 * against a mechanism that cannot be constructed.
 *
 * **AD-730-12 spike record.** Flipping [BpmnToElkMapper.applyParticipantProfile]'s
 * `HIERARCHY_HANDLING` to `SEPARATE_CHILDREN` was measured directly against the full corpus, not
 * against a synthetic graph: it changed `miwg-c2-four-pools` — a non-lane, message-flow-carrying
 * fixture with no cross-participant ELK edge — moving `Task_receive_order` ~40px within its own
 * pool and failing the pinned `CrossParticipantMessageFlowProbeTest` regression guard. Every lane
 * fixture stayed byte-identical. So the hierarchy is not vestigial in the way AD-730-12 hoped:
 * something beyond the excluded message-flow edges still depends on it, even confined to one
 * participant with no compound child of its own. The change was reverted; D1 stands as a known
 * limitation under AD-730-07 (gate 11), and this is not re-attempted without new evidence.
 */
class LaneConstraintSpikeTest {

    init {
        LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
    }

    /**
     * Falsification: partition-tagged lanes under `Direction.RIGHT` land side-by-side (ordered on
     * the flow axis), not stacked as top-to-bottom bands — confirming AD-730-05's partitioning
     * axis analysis before any candidate is tried.
     */
    @Test
    fun `partitioning under Direction RIGHT orders lanes horizontally, not vertically`() {
        val (_, participant) = buildHierarchicalGraph()
        participant.children.forEach { node ->
            val laneIndex = node.identifier.laneIndexOf()
            node.setProperty(LayeredOptions.PARTITIONING_ACTIVATE, true)
            node.setProperty(LayeredOptions.PARTITIONING_PARTITION, laneIndex)
        }
        RecursiveGraphLayoutEngine().layout(participant.parent, BasicProgressMonitor())

        val topX = participant.children.first { it.identifier == "top_a1" }.x
        val lowerX = participant.children.first { it.identifier == "lower_b1" }.x
        assertTrue(
            topX != lowerX,
            "expected partitioning to separate lanes on X (the flow axis) under Direction.RIGHT, " +
                "not Y — got topX=$topX lowerX=$lowerX",
        )
    }

    /**
     * A1 — ordering only: `POSITION.y` = lane order key + `crossingMinimization.semiInteractive`.
     *
     * Measured, not assumed (both a symmetric and a hop-asymmetric two-crossing variant of this
     * topology were tried): on this class of graph, ELK's default global node placer keeps the
     * two crossings *ordered* correctly on its own — A1 does not reliably fail simple ordering,
     * contradicting the original hypothesis. The property that actually distinguishes A1 from A2,
     * and that production genuinely depends on, is *controllable separation*: BPMN lane heights
     * vary by each lane's real content ([BpmnToElkMapper.computeLaneBands]), so the gap between
     * lane groups must equal that *declared* height, not whatever the generic node-node spacing
     * default happens to produce. A1 has no channel to communicate a per-lane declared height; it
     * can only order. This test measures that gap directly.
     */
    @Test
    fun `A1 ordering alone cannot enforce a declared per-lane band height`() {
        val (root, participant) = buildHierarchicalGraph()
        applyA1(participant)
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val maxTopY = participant.children.filter { it.identifier.laneIndexOf() == 0 }.maxOf { it.y }
        val minLowerY = participant.children.filter { it.identifier.laneIndexOf() == 1 }.minOf { it.y }
        val actualGap = minLowerY - maxTopY
        assertTrue(
            actualGap < LANE_BAND_HEIGHT,
            "A1 ordering-only produced gap=$actualGap between lane groups; expected it to fall " +
                "well short of the declared LANE_BAND_HEIGHT=$LANE_BAND_HEIGHT (A1 has no channel " +
                "to communicate a lane's real declared height, only generic node-node spacing) — " +
                "if this gap already matched the declared height, A1 alone would be sufficient",
        )
    }

    /**
     * A2 — ordering + banding: A1 plus each node's *input* `y` set to its lane band offset, with
     * `nodePlacement.strategy = INTERACTIVE`. Measured on the full two-crossing hierarchical
     * graph: every top-lane node's Y must stay below every lower-lane node's Y everywhere in the
     * diagram, not just within one shared layer.
     */
    @Test
    fun `A2 interactive placement bands every lane member across all layers on the hierarchical graph`() {
        val (root, participant) = buildHierarchicalGraph()
        applyA2(participant)
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val maxTopY = participant.children.filter { it.identifier.laneIndexOf() == 0 }.maxOf { it.y }
        val minLowerY = participant.children.filter { it.identifier.laneIndexOf() == 1 }.minOf { it.y }
        assertTrue(
            maxTopY < minLowerY,
            "expected every top-lane node's Y to stay below every lower-lane node's Y across both " +
                "crossings — got maxTopY=$maxTopY minLowerY=$minLowerY",
        )
    }

    /**
     * Greedy-switch risk (AD-730-05), measured on the *actual* production shape this time: the
     * participant compound has `HIERARCHY_HANDLING = INCLUDE_CHILDREN`
     * ([BpmnToElkMapper.applyParticipantProfile]), so `GraphConfigurator.activateGreedySwitchFor`
     * takes its hierarchical branch (`CROSSING_MINIMIZATION_GREEDY_SWITCH_HIERARCHICAL_TYPE`), not
     * the non-hierarchical one the original spike measured.
     */
    @Test
    fun `A2 banding survives the default greedy switch on the hierarchical participant graph`() {
        val (root, participant) = buildHierarchicalGraph()
        applyA2(participant)
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val maxTopY = participant.children.filter { it.identifier.laneIndexOf() == 0 }.maxOf { it.y }
        val minLowerY = participant.children.filter { it.identifier.laneIndexOf() == 1 }.minOf { it.y }
        assertTrue(
            maxTopY < minLowerY,
            "expected the default hierarchical greedy switch to leave A2's lane banding intact " +
                "on the INCLUDE_CHILDREN graph — got maxTopY=$maxTopY minLowerY=$minLowerY",
        )
    }

    /**
     * Long-edge-dummy risk (AD-730-05): an edge spanning several layers (`top_start` direct to
     * `top_end`, skipping every intermediate layer) forces ELK to insert and then remove internal
     * long-edge dummy nodes. A2's banding of the real nodes must survive that insertion/removal.
     */
    @Test
    fun `A2 banding survives a layer-spanning edge that forces long-edge dummy insertion`() {
        val (root, participant) = buildHierarchicalGraph()
        val start = participant.children.first { it.identifier == "top_start" }
        val end = participant.children.first { it.identifier == "top_end" }
        ElkGraphUtil.createSimpleEdge(start, end)
        applyA2(participant)
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val maxTopY = participant.children.filter { it.identifier.laneIndexOf() == 0 }.maxOf { it.y }
        val minLowerY = participant.children.filter { it.identifier.laneIndexOf() == 1 }.minOf { it.y }
        assertTrue(
            maxTopY < minLowerY,
            "expected A2's banding to survive a layer-spanning edge's dummy-node insertion and " +
                "removal — got maxTopY=$maxTopY minLowerY=$minLowerY",
        )
    }

    /**
     * The mechanism-selection gate requires more than banded node positions: it requires evidence
     * that a *cross-lane edge's routed section* is derived from those final normalized positions,
     * not a stale or independently repaired route (AD-730-01). After layout, a cross-lane edge's
     * section must start/end within its source/target node's actual settled bounds.
     */
    @Test
    fun `a cross-lane edge's routed section terminates at the final banded node positions`() {
        val (root, participant) = buildHierarchicalGraph()
        applyA2(participant)
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val split = participant.children.first { it.identifier == "top_splitA" }
        val lower = participant.children.first { it.identifier == "lower_b1" }
        val edge = split.outgoingEdges.first { it.targets.contains(lower) }
        val section = edge.sections.single()

        // ELK reports edge coordinates relative to the edge's containing node; both endpoints
        // here share the participant as their lowest common ancestor, so no further offset walk
        // is needed to compare directly against each node's own (participant-relative) x/y.
        assertTrue(
            section.startX >= split.x && section.startX <= split.x + split.width,
            "the edge's start X must fall within its source node's final settled bounds",
        )
        assertTrue(
            section.endY >= lower.y && section.endY <= lower.y + lower.height,
            "the edge's end Y must fall within its target node's final banded position, not a " +
                "stale pre-banding Y",
        )
    }

    /**
     * Candidate C (AD-730-10, closed) — group model order as an in-layer sort key, with node
     * placement left stock (`NETWORK_SIMPLEX`). AD-730-11's structural argument is that this
     * cannot band a **serial** chain: phase 3 only orders nodes that already share a layer, and a
     * serial flow has exactly one node per layer, so there is nothing for it to order. This is
     * deliberately a different graph from [buildHierarchicalGraph]: that graph's split/join pairs
     * put a `top_*` and `lower_*` node in the *same* layer at each crossing, which gives group
     * order something to act on and is not the degenerate case AD-730-11 describes. A strictly
     * alternating serial chain — one node per layer throughout, matching `collab-lanes` — is.
     * Measured here directly so a future re-proposal fails immediately rather than being
     * re-measured. Full corpus evidence — `collab-lanes` producing a zero-height
     * `Lane_warehouse` band under the identical configuration — is in `plans/730/BLOCKER-730-2.md`.
     */
    @Test
    fun `Candidate C group model order cannot separate lane members of a serial chain`() {
        val root = ElkGraphUtil.createGraph()
        val participant = ElkGraphUtil.createNode(root)
        participant.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        participant.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        participant.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        participant.setProperty(CoreOptions.RANDOM_SEED, 1)
        participant.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        participant.setProperty(CoreOptions.SPACING_NODE_NODE, 60.0)
        participant.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 90.0)
        participant.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
        participant.setProperty(
            LayeredOptions.CONSIDER_MODEL_ORDER_GROUP_MODEL_ORDER_CM_GROUP_ORDER_STRATEGY,
            GroupOrderStrategy.ENFORCED,
        )
        participant.setProperty(
            LayeredOptions.CONSIDER_MODEL_ORDER_GROUP_MODEL_ORDER_CM_ENFORCED_GROUP_ORDERS,
            listOf(0, 1),
        )

        // top_1 -> lower_1 -> top_2 -> lower_2: strictly serial, one node per layer, alternating
        // lanes at every step — the degenerate case AD-730-11 argues group order cannot band.
        val ids = listOf("top_1", "lower_1", "top_2", "lower_2")
        val nodes = ids.associateWith { id ->
            ElkGraphUtil.createNode(participant).also {
                it.identifier = id
                it.width = 60.0
                it.height = 40.0
                it.setProperty(
                    LayeredOptions.CONSIDER_MODEL_ORDER_GROUP_MODEL_ORDER_CROSSING_MINIMIZATION_ID,
                    id.laneIndexOf(),
                )
            }
        }
        ids.zipWithNext().forEach { (a, b) -> ElkGraphUtil.createSimpleEdge(nodes.getValue(a), nodes.getValue(b)) }
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        val maxTopY = nodes.values.filter { it.identifier.laneIndexOf() == 0 }.maxOf { it.y }
        val minLowerY = nodes.values.filter { it.identifier.laneIndexOf() == 1 }.minOf { it.y }
        assertTrue(
            maxTopY >= minLowerY,
            "expected group model order alone (stock NETWORK_SIMPLEX placement) to fail to " +
                "separate the two lane groups on a strictly serial chain — got maxTopY=$maxTopY " +
                "minLowerY=$minLowerY; if this now passes, AD-730-11's structural argument needs " +
                "re-examining before reopening the mechanism search",
        )
    }

    /**
     * D2 fix (AD-730-09), isolated from the full corpus: `applyLaneBand`'s
     * `PORT_ALIGNMENT_EAST`/`WEST = CENTER` makes two centred nodes of *different* height attach
     * at the *same* absolute port Y, because `C` (the port-label content height `CENTER` lays
     * ports within) depends only on port count, not node height — so `H` cancels for a centred
     * node. Two same-count-port nodes of different heights sharing a centreline is exactly what a
     * cross-lane gateway-to-task edge is; this reproduces it directly rather than only through a
     * fixture golden.
     */
    @Test
    fun `centred nodes of different height with matching port counts attach at identical absolute port Y`() {
        val root = ElkGraphUtil.createGraph()
        val parent = ElkGraphUtil.createNode(root)
        parent.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        parent.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)

        val centreline = 100.0
        val tall = ElkGraphUtil.createNode(parent).also {
            it.identifier = "tall"
            it.width = 60.0
            it.height = 80.0
            it.y = centreline - it.height / 2
            it.setProperty(CoreOptions.PORT_ALIGNMENT_EAST, PortAlignment.CENTER)
        }
        val short = ElkGraphUtil.createNode(parent).also {
            it.identifier = "short"
            it.width = 60.0
            it.height = 50.0
            it.y = centreline - it.height / 2
            it.setProperty(CoreOptions.PORT_ALIGNMENT_WEST, PortAlignment.CENTER)
        }
        val eastPort = ElkGraphUtil.createPort(tall).also {
            it.setProperty(CoreOptions.PORT_SIDE, org.eclipse.elk.core.options.PortSide.EAST)
        }
        val westPort = ElkGraphUtil.createPort(short).also {
            it.setProperty(CoreOptions.PORT_SIDE, org.eclipse.elk.core.options.PortSide.WEST)
        }
        tall.setProperty(CoreOptions.PORT_CONSTRAINTS, org.eclipse.elk.core.options.PortConstraints.FIXED_SIDE)
        short.setProperty(CoreOptions.PORT_CONSTRAINTS, org.eclipse.elk.core.options.PortConstraints.FIXED_SIDE)
        ElkGraphUtil.createSimpleEdge(eastPort, westPort)
        RecursiveGraphLayoutEngine().layout(root, BasicProgressMonitor())

        assertEquals(
            tall.y + eastPort.y,
            short.y + westPort.y,
            "a centred tall node and a centred short node with one port each on facing sides " +
                "must attach at the same absolute Y once ports are placed",
        )
    }

    private fun applyA1(participant: ElkNode) {
        participant.setProperty(LayeredOptions.CROSSING_MINIMIZATION_SEMI_INTERACTIVE, true)
        participant.children.forEach { node ->
            node.setProperty(LayeredOptions.POSITION, KVector(0.0, node.identifier.laneIndexOf().toDouble()))
        }
    }

    private fun applyA2(participant: ElkNode) {
        applyA1(participant)
        participant.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.INTERACTIVE)
        participant.children.forEach { node -> node.y = node.identifier.laneIndexOf() * LANE_BAND_HEIGHT }
    }

    /** `top_*` nodes are lane 0; `lower_*` nodes are lane 1 — mirrors the regression fixture. */
    private fun String.laneIndexOf(): Int = if (startsWith("lower")) 1 else 0

    /**
     * Builds an outer root containing one participant-shaped compound
     * (`HIERARCHY_HANDLING = INCLUDE_CHILDREN`, algorithm/direction/routing set exactly as
     * [BpmnToElkMapper.applyParticipantProfile]/`applyRootLayoutOptions` do), with the
     * two-crossing branch/rejoin topology as its direct children — the AD-730-06 target shape:
     * no lane compound, real hierarchical nesting matching production.
     *
     * @return (outer root to hand to the layout engine, the participant compound to configure).
     */
    private fun buildHierarchicalGraph(): Pair<ElkNode, ElkNode> {
        val root = ElkGraphUtil.createGraph()
        val participant = ElkGraphUtil.createNode(root)
        participant.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        participant.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        participant.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        participant.setProperty(CoreOptions.RANDOM_SEED, 1)
        participant.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        participant.setProperty(CoreOptions.SPACING_NODE_NODE, 60.0)
        participant.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, 90.0)

        // Deliberately asymmetric: crossing A's top branch takes an extra hop (top_b1 ->
        // top_b1x -> join) while its lower branch takes one hop directly to the join; crossing
        // B is the reverse (extra hop on its lower branch, one hop on top). A perfectly
        // symmetric two-crossing graph let a non-interactive placer coincidentally align both
        // crossings (measured, then rejected as unrepresentative) — real BPMN branches are not
        // hop-symmetric, so this shape is the fairer adversarial test of A1 alone.
        val ids = listOf(
            "top_start", "top_a1", "top_splitA", "top_b1", "top_b1x", "lower_b1", "top_joinA",
            "top_a2", "top_splitB", "top_b2", "lower_b2", "lower_b2x", "top_joinB", "top_end",
        )
        val nodes = ids.associateWith { id ->
            ElkGraphUtil.createNode(participant).also {
                it.identifier = id
                it.width = 60.0
                it.height = 40.0
            }
        }

        fun edge(fromId: String, toId: String) = ElkGraphUtil.createSimpleEdge(nodes.getValue(fromId), nodes.getValue(toId))
        edge("top_start", "top_a1")
        edge("top_a1", "top_splitA")
        edge("top_splitA", "top_b1")
        edge("top_b1", "top_b1x")
        edge("top_splitA", "lower_b1")
        edge("top_b1x", "top_joinA")
        edge("lower_b1", "top_joinA")
        edge("top_joinA", "top_a2")
        edge("top_a2", "top_splitB")
        edge("top_splitB", "top_b2")
        edge("top_splitB", "lower_b2")
        edge("lower_b2", "lower_b2x")
        edge("top_b2", "top_joinB")
        edge("lower_b2x", "top_joinB")
        edge("top_joinB", "top_end")

        return root to participant
    }

    private companion object {
        const val LANE_BAND_HEIGHT = 200.0
    }
}
