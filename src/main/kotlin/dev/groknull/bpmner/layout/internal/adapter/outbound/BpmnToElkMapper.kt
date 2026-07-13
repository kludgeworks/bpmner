/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.FlowElement
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.graph.ElkConnectableShape
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * Phase 1a — maps a Camunda [BpmnModelInstance] to a LEAN ELK skeleton graph.
 *
 * "Lean" means: flow nodes, sequence flows, subprocess compound nodes, and boundary
 * events as SOUTH-side host ports carrying their exception edges. No [ElkLabel]s, no
 * artifacts in the ELK graph, no message flows. [omitNodeMicroLayout] is set so ELK
 * does not attempt to place node-internal content.
 *
 * Per AD-557-10: ELK owns structural layout only. Labels, boundary-event shape positions,
 * artifacts, and baseline alignment are owned by [BpmnPlacementPass] (phase 2).
 *
 * Runs three ordered passes over the model:
 * 1. [mapProcess] — recursive; builds compound nodes for subprocesses, flat nodes for
 *    other flow nodes. BoundaryEvents are skipped here.
 * 2. [mapBoundaryEvents] — creates a SOUTH port on each host node (for ELK to route the
 *    exception edge) and a sibling node (so the handler edge has a target in nodeMap).
 *    Requires pass 1 to have populated [nodeMap] first.
 * 3. [mapSequenceFlows] — creates ELK edges; checks [portMap] before [nodeMap] for sources
 *    so exception flows route from the boundary port. Requires passes 1 and 2.
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
        val topLevelElements = model.getModelElementsByType(FlowElement::class.java)
            .filter { it.parentElement is org.camunda.bpm.model.bpmn.instance.Process }
            .sortedBy { it.id }
        mapProcess(root, topLevelElements, nodeMap, model)

        // Artifacts tracked for placement pass but NOT added to ELK skeleton.
        trackAnnotations(model, nodeMap)
        trackGroups(model, nodeMap)

        // Pass 2: boundary events (need host nodes from pass 1)
        mapBoundaryEvents(model, nodeMap, portMap)

        // Pass 3: sequence flows (need both nodes and ports)
        mapSequenceFlows(model, nodeMap, portMap, edgeMap)

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
        for (element in elements.sortedBy { it.id }) {
            when (element) {
                is BoundaryEvent -> Unit // handled in pass 2

                is SubProcess -> {
                    val compound = ElkGraphUtil.createNode(container)
                    compound.identifier = element.id
                    compound.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
                    compound.setProperty(CoreOptions.PADDING, ElkPadding(SUBPROCESS_PADDING))
                    // Spacing options do not propagate from the root to child graphs, so the same
                    // label-clearing spacing (Bug A) must be set on each compound node too, or the
                    // inner flow packs tight and its labels collide (e.g. subprocess-loop).
                    applyFlowSpacing(compound)
                    // No ElkLabel on the compound — labels are owned by BpmnPlacementPass.
                    nodeMap[element.id] = compound
                    // Recurse into subprocess children
                    val children = element.flowElements.sortedBy { it.id }
                    mapProcess(compound, children, nodeMap, model)
                }

                is FlowNode -> {
                    val elkNode = ElkGraphUtil.createNode(container)
                    elkNode.identifier = element.id
                    val (w, h) = nodeDimensions(element)
                    elkNode.width = w
                    elkNode.height = h
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
     * Pass 2: for each [BoundaryEvent], create a SOUTH port on the host node and a
     * sibling node in the host's container.
     *
     * Per AD-557-10: ALL boundary ports are SOUTH (not cycling through sides).
     * ELK routes exception edges out the bottom of the host with crossing-minimisation.
     * Multiple attachments on the same host all use SOUTH — the placement pass distributes
     * their shapes evenly along the host's bottom edge in phase 2.
     *
     * The sibling node is needed so the exception edge's target (the handler) has a
     * nodeMap entry; it also serves as a size carrier for phase-2 shape placement.
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
     * Pass 3: map sequence flows to ELK edges. For boundary event sources, uses the port
     * (from [portMap]) as the source so ELK routes from the attachment point. For all other
     * sources uses the node from [nodeMap]. [ElkGraphUtil.createSimpleEdge] computes the
     * LCA container automatically.
     *
     * No edge labels are added to the ELK graph — edge labels are phase-2 responsibility.
     */
    private fun mapSequenceFlows(
        model: BpmnModelInstance,
        nodeMap: Map<String, ElkNode>,
        portMap: Map<String, ElkPort>,
        edgeMap: MutableMap<String, ElkEdge>,
    ) {
        for (sf in model.getModelElementsByType(SequenceFlow::class.java).sortedBy { it.id }) {
            val sourceId = sf.source?.id
            val targetId = sf.target?.id

            val source: ElkConnectableShape = portMap[sourceId]
                ?: nodeMap[sourceId]
                ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' source '$sourceId' not found")
            val target: ElkConnectableShape = nodeMap[targetId]
                ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' target '$targetId' not found")

            // Skip edges to/from artifact tracker nodes (detached nodes with no parent in the ELK graph).
            // A detached ElkNode (created with ElkGraphUtil.createGraph() for size-tracking only)
            // has a null parent; real ELK graph nodes always have a parent.
            val sourceIsArtifact = (source as? ElkNode)?.parent == null && source !is ElkPort
            val targetIsArtifact = (target as? ElkNode)?.parent == null
            if (sourceIsArtifact || targetIsArtifact) continue

            val elkEdge = ElkGraphUtil.createSimpleEdge(source, target)
            elkEdge.identifier = sf.id
            // No edge label added here — phase 2 (BpmnPlacementPass) owns edge labels.
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
        // separateConnectedComponents: disconnected subgraphs (e.g. exception paths) are
        // laid out as separate components (AD-557-10 prior-art tables).
        root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, true)
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
            else -> Pair(TASK_WIDTH, TASK_HEIGHT)
        }
    }

    private const val RANDOM_SEED = 1

    private const val TASK_WIDTH = 100.0
    private const val TASK_HEIGHT = 80.0
    internal const val EVENT_SIZE = 36.0
    private const val GATEWAY_SIZE = 50.0
    private const val ANNOTATION_WIDTH = 100.0
    private const val ANNOTATION_HEIGHT = 60.0
    private const val GROUP_WIDTH = 300.0
    private const val GROUP_HEIGHT = 200.0
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
