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
 * Maps a Camunda [BpmnModelInstance] to a lean ELK skeleton graph.
 *
 * Flow nodes, sequence flows, subprocess compound nodes, and boundary events as SOUTH-side host ports
 * are mapped to the ELK graph. ELK owns structural layout only. Exception edges, labels, boundary-event
 * shape positions, and artifacts are positioned by [BpmnPlacementPass].
 *
 * Maps the process, boundary events, and sequence flows, and tracks artifacts (TextAnnotation, Group)
 * in the node map for placement.
 */
internal object BpmnToElkMapper {

    /**
     * The raw ELK skeleton graph after layout.
     *
     * [loopBackFlowIds] carries the set of sequence-flow IDs that were excluded from the ELK
     * skeleton (back-edges in cyclic subprocesses).
     */
    internal data class ElkSkeleton(
        val root: ElkNode,
        val nodeMap: Map<String, ElkNode>,
        val portMap: Map<String, ElkPort>,
        val edgeMap: Map<String, ElkEdge>,
        val loopBackFlowIds: Set<String> = emptySet(),
    )

    fun map(model: BpmnModelInstance): ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        applyRootLayoutOptions(root)
        val nodeMap = mutableMapOf<String, ElkNode>()
        val portMap = mutableMapOf<String, ElkPort>()
        val edgeMap = mutableMapOf<String, ElkEdge>()

        val loopBackFlowIds = mutableSetOf<String>()
        val loopingSubIds = mutableSetOf<String>()
        model.getModelElementsByType(SubProcess::class.java).forEach { sub ->
            val backEdges = findLoopBackEdges(sub)
            if (backEdges.isNotEmpty()) {
                loopBackFlowIds.addAll(backEdges)
                loopingSubIds.add(sub.id)
            }
        }

        val topLevelElements = model.getModelElementsByType(FlowElement::class.java)
            .filter { it.parentElement is org.camunda.bpm.model.bpmn.instance.Process }
        mapProcess(root, topLevelElements, nodeMap, model, loopingSubIds)

        trackAnnotations(model, nodeMap)
        trackGroups(model, nodeMap)

        mapBoundaryEvents(model, nodeMap, portMap)

        mapSequenceFlows(model, nodeMap, edgeMap, loopBackFlowIds)

        return ElkSkeleton(root, nodeMap, portMap, edgeMap, loopBackFlowIds)
    }

    /**
     * Recursively maps flow elements into the given [container].
     * SubProcesses become compound ELK nodes and recurse; BoundaryEvents are skipped
     * (handled in pass 2); other FlowNodes become flat leaf nodes.
     * No labels are added to the ELK graph — labels are phase-2 responsibility.
     *
     * [loopingSubIds] is the set of subprocess IDs that have back-edges (pre-computed in [map]).
     * Cyclic subprocesses get extra top padding so the phase-2 loop-back arc has clear headroom.
     */
    private fun mapProcess(
        container: ElkNode,
        elements: List<FlowElement>,
        nodeMap: MutableMap<String, ElkNode>,
        model: BpmnModelInstance,
        loopingSubIds: Set<String> = emptySet(),
    ) {
        // Iterate in document order (elements list preserves Camunda's XML parse order).
        for (element in elements) {
            when (element) {
                is BoundaryEvent -> Unit // handled in pass 2

                is SubProcess -> {
                    val compound = ElkGraphUtil.createNode(container)
                    compound.identifier = element.id
                    compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
                    val topPadding = if (element.id in loopingSubIds) SUBPROCESS_TOP_PADDING else SUBPROCESS_PADDING
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
                    // No ElkLabel on the compound — labels are managed during placement.
                    nodeMap[element.id] = compound
                    // Recurse into subprocess children in document order.
                    mapProcess(compound, element.flowElements.toList(), nodeMap, model, loopingSubIds)
                }

                is FlowNode -> {
                    val elkNode = ElkGraphUtil.createNode(container)
                    elkNode.identifier = element.id
                    val (w, h) = nodeDimensions(element)
                    elkNode.width = w
                    elkNode.height = h
                    // Pin start events to the first layer, end events to the last.
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
                    // No ElkLabel — labels are managed during placement.
                    nodeMap[element.id] = elkNode
                }

                else -> Unit // TextAnnotation/Group are Artifacts, tracked separately
            }
        }
    }

    /**
     * Tracks TextAnnotations in [nodeMap] with their placeholder sizes.
     * The nodeMap entry lets the placement pass find their dimensions.
     */
    private fun trackAnnotations(
        model: BpmnModelInstance,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (ann in model.getModelElementsByType(TextAnnotation::class.java).sortedBy { it.id }) {
            // Use a detached ElkNode (no parent) to carry the size info.
            val elkNode = ElkGraphUtil.createGraph() // detached root size carrier
            elkNode.identifier = ann.id
            elkNode.width = ANNOTATION_WIDTH
            elkNode.height = ANNOTATION_HEIGHT
            nodeMap[ann.id] = elkNode
        }
    }

    /**
     * Tracks Groups in [nodeMap] with their placeholder sizes.
     */
    private fun trackGroups(
        model: BpmnModelInstance,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (group in model.getModelElementsByType(Group::class.java).sortedBy { it.id }) {
            val elkNode = ElkGraphUtil.createGraph() // detached root size carrier
            elkNode.identifier = group.id
            elkNode.width = GROUP_WIDTH
            elkNode.height = GROUP_HEIGHT
            nodeMap[group.id] = elkNode
        }
    }

    /**
     * For each [BoundaryEvent], create a SOUTH port on the host node (attachment
     * geometry only — no ELK edge from this port) and a sibling node for the
     * handler in the host's container.
     *
     * All boundary ports are SOUTH. The port communicates the attachment side to ELK
     * but carries no exception edge. The sibling handler node has no incoming ELK
     * edge, making it a disconnected component placed below the main flow.
     *
     * The sibling node serves as a size carrier for shape placement and as a target
     * anchor for the exception edge route.
     */
    private fun mapBoundaryEvents(
        model: BpmnModelInstance,
        nodeMap: MutableMap<String, ElkNode>,
        portMap: MutableMap<String, ElkPort>,
    ) {
        val boundaryEvents = model.getModelElementsByType(BoundaryEvent::class.java).sortedBy { it.id }
        for (be in boundaryEvents) {
            val hostId = be.attachedTo?.id
            val hostNode = hostId?.let { nodeMap[it] }
            val container = hostNode?.parent
            if (hostId == null || hostNode == null || container == null) {
                val reason = when {
                    hostId == null -> "boundary event '${be.id}' has no attachedToRef"
                    hostNode == null -> "boundary event '${be.id}' host '$hostId' not found in nodeMap"
                    else -> "host node '$hostId' has no parent container"
                }
                throw BpmnAutoLayoutException("ELK layout: $reason")
            }

            // Port on the host: ALWAYS SOUTH (all exception edges exit bottom).
            // Multiple attachments share SOUTH; the placement pass handles their shape distribution.
            hostNode.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
            val port = ElkGraphUtil.createPort(hostNode)
            port.identifier = "port_${be.id}"
            port.width = BOUNDARY_PORT_SIZE
            port.height = BOUNDARY_PORT_SIZE
            port.setProperty(CoreOptions.PORT_SIDE, PortSide.SOUTH)
            portMap[be.id] = port

            // Sibling node in the host's container — size carrier for shape placement.
            // Not used as an ELK layered node for routing; the port handles routing.
            val beNode = ElkGraphUtil.createNode(container)
            beNode.identifier = be.id
            beNode.width = EVENT_SIZE
            beNode.height = EVENT_SIZE
            // No ElkLabel — labels are managed during placement.
            nodeMap[be.id] = beNode
        }
    }

    /**
     * Maps sequence flows to ELK edges for process-flow nodes only.
     *
     * Flows whose source is a [BoundaryEvent] are not added to the ELK skeleton.
     * Loop-back edges (back-edges that create cycles in subprocess flows) are also excluded
     * so that the acyclic forward path is layouted by ELK.
     *
     * The [loopBackFlowIds] set is pre-computed by [map] and passed in to avoid recomputation.
     */
    private fun mapSequenceFlows(
        model: BpmnModelInstance,
        nodeMap: Map<String, ElkNode>,
        edgeMap: MutableMap<String, ElkEdge>,
        loopBackFlowIds: Set<String>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        model.getModelElementsByType(SequenceFlow::class.java)
            .filterNot { it.source?.id in boundaryIds }
            .filterNot { it.id in loopBackFlowIds }
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

    /**
     * Returns the IDs of sequence flows that are back-edges in the subprocess (i.e. create
     * cycles). Uses an iterative DFS with an explicit call-stack to avoid a local fun declaration
     * (which would count against the TooManyFunctions detekt limit).
     *
     * Each stack frame is (nodeId, iteratorIndex): when the iterator is exhausted the node is
     * popped from the DFS ancestor-stack and marked fully visited.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    private fun findLoopBackEdges(sub: SubProcess): Set<String> {
        val flows = sub.flowElements.filterIsInstance<SequenceFlow>()
        val succFlows = mutableMapOf<String, MutableList<Pair<String, String>>>() // nodeId → [(targetId, flowId)]
        flows.forEach { sf ->
            val s = sf.source?.id ?: return@forEach
            val t = sf.target?.id ?: return@forEach
            succFlows.getOrPut(s) { mutableListOf() }.add(t to sf.id)
        }

        val startEvents = sub.flowElements.filterIsInstance<StartEvent>()
        val seeds = if (startEvents.isNotEmpty()) {
            startEvents.map { it.id }
        } else {
            sub.flowElements.filterIsInstance<FlowNode>().map { it.id }
        }

        val backEdges = mutableSetOf<String>()
        val visited = mutableSetOf<String>() // fully processed nodes

        for (seed in seeds) {
            if (seed in visited) continue

            val ancestors = mutableSetOf<String>() // nodes on the current DFS path
            val callStack = ArrayDeque<Pair<String, Int>>()

            visited.add(seed)
            ancestors.add(seed)
            callStack.addLast(seed to 0)

            while (callStack.isNotEmpty()) {
                val (nodeId, idx) = callStack.last()
                val neighbours = succFlows[nodeId].orEmpty()
                if (idx >= neighbours.size) {
                    callStack.removeLast()
                    ancestors.remove(nodeId)
                } else {
                    callStack[callStack.size - 1] = nodeId to (idx + 1)
                    val (targetId, flowId) = neighbours[idx]
                    when {
                        targetId in ancestors -> backEdges.add(flowId) // back-edge
                        targetId !in visited -> {
                            visited.add(targetId)
                            ancestors.add(targetId)
                            callStack.addLast(targetId to 0)
                        }
                    }
                }
            }
        }
        return backEdges
    }

    private fun applyRootLayoutOptions(root: ElkNode) {
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        root.setProperty(CoreOptions.RANDOM_SEED, RANDOM_SEED)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
        applyFlowSpacing(root)
        // Prevent ELK from placing node-internal labels/ports, as custom sizes are used.
        root.setProperty(CoreOptions.OMIT_NODE_MICRO_LAYOUT, true)
        // Handler nodes (no incoming ELK edge) become disconnected components placed below.
        root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, true)
        // NETWORK_SIMPLEX keeps the primary flow on a single Y baseline.
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
        // Use model order for deterministic, document-order branch ordering.
        root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
        // Ensure adequate spacing between connected components.
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
}
