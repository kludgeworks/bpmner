/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.FlowElement
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.eclipse.elk.alg.layered.options.LayerConstraint
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * Phase 1a — maps a Camunda [BpmnModelInstance] to a LEAN ELK skeleton graph.
 *
 * "Lean" means: flow nodes, sequence flows, subprocess compound nodes, and boundary
 * events as SOUTH-side host ports (attachment geometry only — no exception edges in ELK).
 * No [ElkLabel]s, no artifacts, no message flows. [omitNodeMicroLayout] is set so ELK
 * does not attempt to place node-internal content.
 *
 * Per AD-557-10/AD-557-12: ELK owns structural layout only. Exception edges, labels,
 * boundary-event shape positions, and artifacts are owned by [BpmnPlacementPass] (phase 2).
 * Handler nodes are genuinely disconnected ELK components; ELK's SimpleRowGraphPlacer
 * stacks them below the main flow automatically (no post-ELK node move).
 *
 * Runs three ordered passes over the model:
 * 1. [mapProcess] — recursive; builds compound nodes for subprocesses, flat nodes for
 *    other flow nodes. BoundaryEvents are skipped here.
 * 2. [mapBoundaryEvents] — creates a SOUTH port on each host node (attachment geometry only;
 *    NO ELK edge from the port) and a sibling node for the handler (a disconnected component).
 *    Requires pass 1 to have populated [nodeMap] first.
 * 3. [mapSequenceFlows] — creates ELK edges for sequence flows only. Flows whose source is a
 *    BoundaryEvent are skipped (they are phase-2 bespoke-routed). Requires passes 1 and 2.
 *
 * Artifacts (TextAnnotation, Group) are tracked in [nodeMap] with placeholder sizes for
 * the placement pass to position, but are NOT added to the ELK graph.
 *
 * The result is an [ElkSkeleton] carrying all identity maps needed by [BpmnPlacementPass].
 */
internal object BpmnToElkMapper {

    /**
     * The mapper→placement-pass seam: the raw ELK skeleton after layout.
     * Phase 2 ([BpmnPlacementPass]) reads ELK's output through this object.
     */
    internal data class ElkSkeleton(
        val root: ElkNode,
        val nodeMap: Map<String, ElkNode>,
        val portMap: Map<String, ElkPort>,
        val edgeMap: Map<String, ElkEdge>,
    )

    fun map(model: BpmnModelInstance): ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        applyRootLayoutOptions(root)
        val nodeMap = mutableMapOf<String, ElkNode>()
        val portMap = mutableMapOf<String, ElkPort>()
        val edgeMap = mutableMapOf<String, ElkEdge>()

        // Pass 1: build node tree (recursive for subprocesses).
        // FlowElements covers FlowNodes (tasks, events, gateways) and SubProcess.
        // TextAnnotation and Group are Artifacts, not FlowElements — tracked separately.
        // Document order (Camunda preserves XML declaration order) so CONSIDER_MODEL_ORDER is
        // meaningful — alphabetical id sort would produce arbitrary layout ordering.
        val topLevelElements = model.getModelElementsByType(FlowElement::class.java)
            .filter { it.parentElement is org.camunda.bpm.model.bpmn.instance.Process }
        mapProcess(root, topLevelElements, nodeMap, model)

        // Artifacts tracked for placement pass but NOT added to ELK skeleton.
        trackAnnotations(model, nodeMap)
        trackGroups(model, nodeMap)

        // Pass 2: boundary events (need host nodes from pass 1)
        mapBoundaryEvents(model, nodeMap, portMap)

        // Pass 3: sequence flows (process-flow nodes only — boundary exception edges are phase-2)
        mapSequenceFlows(model, nodeMap, edgeMap)

        return ElkSkeleton(root, nodeMap, portMap, edgeMap)
    }

    /**
     * Recursively maps flow elements into the given [container].
     * SubProcesses become compound ELK nodes and recurse; BoundaryEvents are skipped
     * (handled in pass 2); other FlowNodes become flat leaf nodes.
     * No labels are added to the ELK graph — labels are phase-2 responsibility.
     */
    private fun mapProcess(
        container: ElkNode,
        elements: List<FlowElement>,
        nodeMap: MutableMap<String, ElkNode>,
        model: BpmnModelInstance,
    ) {
        // Iterate in document order (elements list preserves Camunda's XML parse order).
        for (element in elements) {
            when (element) {
                is BoundaryEvent -> Unit // handled in pass 2

                is SubProcess -> {
                    val compound = ElkGraphUtil.createNode(container)
                    compound.identifier = element.id
                    compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
                    val topPadding = if (hasLoop(element)) SUBPROCESS_TOP_PADDING else SUBPROCESS_PADDING
                    compound.setProperty(
                        CoreOptions.PADDING,
                        ElkPadding(
                            topPadding,
                            SUBPROCESS_PADDING,
                            SUBPROCESS_PADDING,
                            SUBPROCESS_PADDING,
                        ),
                    )
                    // Spacing options do not propagate from the root to child graphs, so the same
                    // label-clearing spacing must be set on each compound node too.
                    applyFlowSpacing(compound)
                    // No ElkLabel on the compound — labels are owned by BpmnPlacementPass.
                    nodeMap[element.id] = compound
                    // Recurse into subprocess children in document order.
                    mapProcess(compound, element.flowElements.toList(), nodeMap, model)
                }

                is FlowNode -> {
                    val elkNode = ElkGraphUtil.createNode(container)
                    elkNode.identifier = element.id
                    val (w, h) = nodeDimensions(element)
                    elkNode.width = w
                    elkNode.height = h
                    // AD-557-11: pin start events to the first layer, end events to the last.
                    when (element) {
                        is StartEvent -> elkNode.setProperty(
                            LayeredOptions.LAYERING_LAYER_CONSTRAINT,
                            LayerConstraint.FIRST,
                        )
                        is EndEvent -> elkNode.setProperty(
                            LayeredOptions.LAYERING_LAYER_CONSTRAINT,
                            LayerConstraint.LAST,
                        )
                        else -> Unit
                    }
                    // No ElkLabel — labels are owned by BpmnPlacementPass.
                    nodeMap[element.id] = elkNode
                }

                else -> Unit // TextAnnotation/Group are Artifacts, tracked separately
            }
        }
    }

    /**
     * Tracks TextAnnotations in [nodeMap] with their placeholder sizes.
     * They are NOT added to the ELK graph — the placement pass positions them as
     * sidecar geometry. The nodeMap entry lets the placement pass find their dimensions.
     */
    private fun trackAnnotations(
        model: BpmnModelInstance,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (ann in model.getModelElementsByType(TextAnnotation::class.java).sortedBy { it.id }) {
            // Use a detached ElkNode (no parent) to carry the size info for the placement pass.
            // It will NOT be in the ELK graph; the placement pass reads it from nodeMap.
            val elkNode = ElkGraphUtil.createGraph() // detached root — just a size carrier
            elkNode.identifier = ann.id
            elkNode.width = ANNOTATION_WIDTH
            elkNode.height = ANNOTATION_HEIGHT
            nodeMap[ann.id] = elkNode
        }
    }

    /**
     * Tracks Groups in [nodeMap] with their placeholder sizes (not in ELK graph).
     */
    private fun trackGroups(
        model: BpmnModelInstance,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (group in model.getModelElementsByType(Group::class.java).sortedBy { it.id }) {
            val elkNode = ElkGraphUtil.createGraph() // detached root — size carrier only
            elkNode.identifier = group.id
            elkNode.width = GROUP_WIDTH
            elkNode.height = GROUP_HEIGHT
            nodeMap[group.id] = elkNode
        }
    }

    /**
     * Pass 2: for each [BoundaryEvent], create a SOUTH port on the host node (attachment
     * geometry only — NO ELK edge from this port, per AD-557-12) and a sibling node for the
     * handler in the host's container.
     *
     * ALL boundary ports are SOUTH (per AD-557-10). The port communicates the attachment
     * side to ELK but carries no exception edge. The sibling handler node has no incoming ELK
     * edge, making it a genuinely disconnected component that ELK's SimpleRowGraphPlacer
     * stacks below the main flow automatically.
     *
     * The sibling node serves as a size carrier for phase-2 shape placement and as a target
     * anchor for the phase-2 bespoke exception edge route.
     */
    @Suppress("ThrowsCount") // three distinct precondition failures
    private fun mapBoundaryEvents(
        model: BpmnModelInstance,
        nodeMap: MutableMap<String, ElkNode>,
        portMap: MutableMap<String, ElkPort>,
    ) {
        val boundaryEvents = model.getModelElementsByType(BoundaryEvent::class.java).sortedBy { it.id }
        for (be in boundaryEvents) {
            val hostId = be.attachedTo?.id ?: throw BpmnAutoLayoutException(
                "ELK layout: boundary event '${be.id}' has no attachedToRef",
            )
            val hostNode = nodeMap[hostId] ?: throw BpmnAutoLayoutException(
                "ELK layout: boundary event '${be.id}' host '$hostId' not found in nodeMap",
            )

            // Port on the host: ALWAYS SOUTH (AD-557-10 — all exception edges exit bottom).
            // Multiple attachments share SOUTH; the placement pass handles their shape distribution.
            val port = ElkGraphUtil.createPort(hostNode)
            port.identifier = "port_${be.id}"
            port.width = BOUNDARY_PORT_SIZE
            port.height = BOUNDARY_PORT_SIZE
            port.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
            port.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH)
            portMap[be.id] = port

            // Sibling node in the host's container — size carrier for phase-2 shape placement.
            // Not used as an ELK layered node for routing; the port handles routing.
            val container = hostNode.parent ?: throw BpmnAutoLayoutException(
                "ELK layout: host node '$hostId' has no parent container",
            )
            val beNode = ElkGraphUtil.createNode(container)
            beNode.identifier = be.id
            beNode.width = EVENT_SIZE
            beNode.height = EVENT_SIZE
            // No ElkLabel — labels are owned by BpmnPlacementPass.
            nodeMap[be.id] = beNode
        }
    }

    /**
     * Pass 3: map sequence flows to ELK edges for process-flow nodes only.
     *
     * AD-557-12: flows whose source is a [BoundaryEvent] are NOT added to the ELK skeleton —
     * they are phase-2 bespoke-routed (three-point orthogonal polyline). This keeps handler
     * nodes as genuinely disconnected ELK components so SimpleRowGraphPlacer stacks them below.
     *
     * All other flows use the node from [nodeMap] as their source. [ElkGraphUtil.createSimpleEdge]
     * computes the LCA container automatically. No edge labels are added — phase-2 responsibility.
     */
    private fun mapSequenceFlows(
        model: BpmnModelInstance,
        nodeMap: Map<String, ElkNode>,
        edgeMap: MutableMap<String, ElkEdge>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        // AD-557-12: skip exception edges (boundary → handler) — they are phase-2 bespoke-routed.
        // Skip artifact edges — artifact tracker nodes are detached (null parent) and have no ELK slot.
        model.getModelElementsByType(SequenceFlow::class.java)
            .filterNot { it.source?.id in boundaryIds }
            .forEach { sf ->
                val sourceId = sf.source?.id
                val targetId = sf.target?.id
                val source = nodeMap[sourceId]
                    ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' source '$sourceId' not found")
                val target = nodeMap[targetId]
                    ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' target '$targetId' not found")
                if (source.parent == null || target.parent == null) return@forEach
                val elkEdge = ElkGraphUtil.createSimpleEdge(source, target)
                elkEdge.identifier = sf.id
                edgeMap[sf.id] = elkEdge
            }
    }

    private fun applyRootLayoutOptions(root: ElkNode) {
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        root.setProperty(CoreOptions.RANDOM_SEED, RANDOM_SEED)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
        applyFlowSpacing(root)
        // omitNodeMicroLayout: prevents ELK from re-placing node-internal labels/ports —
        // we supply node sizes ourselves (AD-557-10).
        root.setProperty(CoreOptions.OMIT_NODE_MICRO_LAYOUT, true)
        // separateConnectedComponents: handler nodes (no incoming ELK edge per AD-557-12) become
        // disconnected components; SimpleRowGraphPlacer stacks them below the main flow.
        root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, true)
        // AD-557-11: NETWORK_SIMPLEX keeps the primary flow on a single centre-Y (replaces snapBaseline).
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
        // AD-557-11: model order for deterministic, document-order branch ordering.
        root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
        // AD-557-12: component-component spacing ensures adequate clearance between the main-flow
        // component row and handler component rows placed below by SimpleRowGraphPlacer.
        root.setProperty(LayeredOptions.SPACING_COMPONENT_COMPONENT, NODE_NODE_SPACING)
    }

    /**
     * Applies node/edge spacing that leaves room for the external labels the placement pass adds
     * below each node (90x20). ELK does not see those labels (they are phase-2), so its spacing is
     * widened to reserve the space itself:
     *  - in-layer (vertical for RIGHT): a node's below-label must clear the node beneath it.
     *  - between-layer (horizontal): a node's 90px-wide centred label must not reach the next
     *    layer's node, and edges must be long enough to be visible.
     *
     * Must be applied to the root AND to every subprocess compound node, because ELK spacing does
     * not propagate from a parent graph to its child graphs.
     */
    private fun applyFlowSpacing(node: ElkNode) {
        node.setProperty(CoreOptions.SPACING_NODE_NODE, NODE_NODE_SPACING)
        node.setProperty(LayeredOptions.SPACING_NODE_NODE_BETWEEN_LAYERS, NODE_NODE_BETWEEN_LAYERS)
        node.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_NODE_SPACING)
        node.setProperty(LayeredOptions.SPACING_EDGE_NODE_BETWEEN_LAYERS, EDGE_NODE_BETWEEN_LAYERS)
    }

    private fun nodeDimensions(flowNode: FlowNode): Pair<Double, Double> {
        val typeName = flowNode.elementType.typeName
        return when {
            typeName.contains("Event") -> Pair(EVENT_SIZE, EVENT_SIZE)
            typeName.contains("Gateway") -> Pair(GATEWAY_SIZE, GATEWAY_SIZE)
            else -> {
                val name = flowNode.name
                if (name != null && name.length > LONG_NAME_THRESHOLD) {
                    val extraChars = name.length - LONG_NAME_THRESHOLD
                    val w = minOf(MAX_TASK_WIDTH, TASK_WIDTH + extraChars * EXTRA_CHAR_WIDTH_FACTOR)
                    Pair(w, TASK_HEIGHT)
                } else {
                    Pair(TASK_WIDTH, TASK_HEIGHT)
                }
            }
        }
    }

    private const val RANDOM_SEED = 1
    private const val LONG_NAME_THRESHOLD = 30
    private const val MAX_TASK_WIDTH = 160.0
    private const val EXTRA_CHAR_WIDTH_FACTOR = 2.0

    private const val TASK_WIDTH = 100.0
    private const val TASK_HEIGHT = 80.0
    internal const val EVENT_SIZE = 36.0
    private const val GATEWAY_SIZE = 50.0
    private const val ANNOTATION_WIDTH = 100.0
    private const val ANNOTATION_HEIGHT = 60.0
    private const val GROUP_WIDTH = 300.0
    private const val GROUP_HEIGHT = 200.0
    internal const val SUBPROCESS_TOP_PADDING = 90.0
    internal const val SUBPROCESS_PADDING = 50.0
    internal const val BOUNDARY_PORT_SIZE = 10.0

    // In-layer spacing (vertical for RIGHT direction). Must exceed LABEL_HEIGHT (20) +
    // LABEL_GAP_BELOW (2) so a node's external label clears the node in the row below.
    private const val NODE_NODE_SPACING = 60.0

    // Between-layer spacing (horizontal for RIGHT direction). A node's centred 90px label
    // overhangs ~25px past a 100px task / ~27px past a 36px event on each side; the next
    // layer must start beyond that, and the connecting edge must be long enough to be visible.
    private const val NODE_NODE_BETWEEN_LAYERS = 90.0

    private const val EDGE_NODE_SPACING = 25.0
    private const val EDGE_NODE_BETWEEN_LAYERS = 25.0

    private fun hasLoop(sub: SubProcess): Boolean {
        val flows = sub.flowElements.filterIsInstance<SequenceFlow>()
        val adj = mutableMapOf<String, MutableList<String>>()
        flows.forEach { sf ->
            val s = sf.source?.id ?: return@forEach
            val t = sf.target?.id ?: return@forEach
            adj.getOrPut(s) { mutableListOf() }.add(t)
        }
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun dfs(node: String): Boolean {
            if (node in stack) return true
            if (node in visited) return false
            visited.add(node)
            stack.add(node)
            for (next in adj[node].orEmpty()) {
                if (dfs(next)) return true
            }
            stack.remove(node)
            return false
        }

        return adj.keys.any { dfs(it) }
    }
}
