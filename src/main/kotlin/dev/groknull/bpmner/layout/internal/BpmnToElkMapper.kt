/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.FlowElement
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.StartEvent
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.eclipse.elk.alg.layered.components.ComponentOrderingStrategy
import org.eclipse.elk.alg.layered.options.CenterEdgeLabelPlacementStrategy
import org.eclipse.elk.alg.layered.options.LayerConstraint
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.alg.layered.options.NodePlacementStrategy
import org.eclipse.elk.alg.layered.options.OrderingStrategy
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.math.KVector
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeLabelPlacement
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.core.options.NodeLabelPlacement
import org.eclipse.elk.core.options.PortConstraints
import org.eclipse.elk.core.options.PortSide
import org.eclipse.elk.core.options.SizeConstraint
import org.eclipse.elk.graph.ElkConnectableShape
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort
import org.eclipse.elk.graph.util.ElkGraphUtil
import java.util.EnumSet

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
// map(), mapProcess/mapCollaboration, mapBoundaryEvents, mapSequenceFlows, trackAnnotations/trackGroups,
// plus options helpers — all share nodeMap/portMap/edgeMap/reversedFlowIds and cannot be split without
// threading those mutable maps through every call or converting them to class fields (which would
// introduce statefulness between calls). Suppression is structural, not incidental.
@Suppress("TooManyFunctions")
internal object BpmnToElkMapper {

    /**
     * The raw ELK skeleton graph after layout.
     *
     * [reversedFlowIds] carries the set of sequence-flow IDs that are back-edges (in cyclic
     * subprocesses/participant processes) and were emitted into the ELK graph with source and
     * target swapped, per AD-622-15, so the graph handed to ELK is acyclic by construction.
     */
    internal data class ElkSkeleton(
        val root: ElkNode,
        val nodeMap: Map<String, ElkNode>,
        val portMap: Map<String, ElkPort>,
        val edgeMap: Map<String, ElkEdge>,
        val reversedFlowIds: Set<String> = emptySet(),
    )

    fun map(model: BpmnModelInstance): ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        applyRootLayoutOptions(root)
        val nodeMap = mutableMapOf<String, ElkNode>()
        val portMap = mutableMapOf<String, ElkPort>()
        val edgeMap = mutableMapOf<String, ElkEdge>()

        // Back-edges are found up front so mapSequenceFlows can emit them reversed (AD-622-15):
        // the graph handed to ELK is acyclic by construction, so cycle-breaking never runs.
        val reversedFlowIds = mutableSetOf<String>()
        model.getModelElementsByType(SubProcess::class.java).forEach { sub ->
            reversedFlowIds.addAll(findLoopBackEdges(sub.flowElements))
        }

        val collaboration = model.getModelElementsByType(Collaboration::class.java).firstOrNull()
        val topProcess = model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process::class.java).firstOrNull()
        if (collaboration != null) {
            // A cyclic sequence flow directly inside a Participant's process is a back-edge too.
            collaboration.participants.mapNotNull { it.process }.forEach { process ->
                reversedFlowIds.addAll(findLoopBackEdges(process.flowElements))
            }
            root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
            mapCollaboration(root, collaboration, model, nodeMap)
        } else if (topProcess != null) {
            // A cyclic sequence flow directly in the top-level process (no collaboration, no
            // enclosing SubProcess) is a back-edge too — the graph is acyclic by construction at
            // every level, root included, not just inside compound nodes.
            reversedFlowIds.addAll(findLoopBackEdges(topProcess.flowElements))
            mapProcess(root, topProcess.flowElements.toList(), nodeMap, model)
        }

        trackAnnotations(model, nodeMap)
        trackGroups(model, nodeMap)

        mapBoundaryEvents(model, nodeMap, portMap)

        mapSequenceFlows(model, nodeMap, portMap, edgeMap, reversedFlowIds)
        collaboration?.let { mapMessageFlows(root, it, nodeMap, edgeMap) }

        return ElkSkeleton(root, nodeMap, portMap, edgeMap, reversedFlowIds)
    }

    /**
     * Maps a collaboration to the ELK graph.
     *
     * Each white-box [Participant] becomes a compound ELK node containing lane compounds and
     * unassigned process members. Black-box participants (no processRef) are root children sized
     * for their message-flow endpoints.
     *
     * Message flows are added after all participant contents have been mapped. Their
     * endpoints may have different compound parents; ELK places the edge at their
     * lowest common ancestor and emits the hierarchy-aware routing sections.
     *
     */
    private fun mapCollaboration(
        root: ElkNode,
        collaboration: Collaboration,
        model: BpmnModelInstance,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (participant in collaboration.participants) {
            val process = participant.process
            if (process != null) {
                // White-box participant: compound ELK node containing the process graph.
                val compound = ElkGraphUtil.createNode(root)
                compound.identifier = participant.id
                applyParticipantProfile(compound)
                nodeMap[participant.id] = compound

                val topLevelElements = process.flowElements.toList()
                val laneMemberIds = mutableSetOf<String>()
                process.laneSets.flatMap { it.lanes.toList() }.forEach { lane ->
                    laneMemberIds.addAll(lane.flowNodeRefs.map { it.id })
                    mapLane(compound, lane, nodeMap, model)
                }
                mapProcess(
                    compound,
                    topLevelElements.filter { it !is FlowNode || it.id !in laneMemberIds },
                    nodeMap,
                    model,
                )
            } else {
                // Black-box participants participate in collaboration-level message edges.
                val bb = ElkGraphUtil.createNode(root)
                bb.identifier = participant.id
                bb.width = BLACK_BOX_WIDTH
                bb.height = BLACK_BOX_HEIGHT
                nodeMap[participant.id] = bb
            }
        }
    }

    // L5 (622-Y): nested lanes (tLane/childLaneSet) are outside this epic's supported
    // profile. A lane that delegates to a childLaneSet carries no flowNodeRefs of its own,
    // so mapping it unguarded silently produces a zero-height ghost lane while its
    // descendants escape to the top level — a broken diagram with no error. Fail loudly
    // instead (AD-622-10's "assert, don't mutate" applied to a construct this epic never
    // implements, not one it renders wrong).
    private fun mapLane(
        participant: ElkNode,
        lane: Lane,
        nodeMap: MutableMap<String, ElkNode>,
        model: BpmnModelInstance,
    ) {
        if (lane.childLaneSet != null) {
            throw BpmnAutoLayoutException("ELK layout: nested lanes (lane '${lane.id}' has a childLaneSet) are not supported")
        }
        val compound = ElkGraphUtil.createNode(participant)
        compound.identifier = lane.id
        applyLaneProfile(compound, lane)
        nodeMap[lane.id] = compound
        mapProcess(compound, lane.flowNodeRefs.toList(), nodeMap, model)
    }

    /**
     * Recursively maps flow elements into the given [container].
     * SubProcesses become compound ELK nodes and recurse; BoundaryEvents are skipped
     * (handled in pass 2); other FlowNodes become flat leaf nodes.
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
                    compound.setProperty(
                        CoreOptions.PADDING,
                        ElkPadding(
                            SUBPROCESS_PADDING,
                            SUBPROCESS_PADDING,
                            SUBPROCESS_PADDING,
                            SUBPROCESS_PADDING,
                        ),
                    )
                    // Spacing options do not propagate from the root to child graphs.
                    applyCompoundProfile(compound)
                    addNodeLabel(compound, element.name)
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
                    addNodeLabel(elkNode, element.name)
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
     * For each [BoundaryEvent], create a SOUTH port on the host node and a sibling node in the
     * host's container. The port is the real routing endpoint: [mapSequenceFlows] draws the
     * exception edge from this port to the handler, so ELK lays out the handler chain itself
     * (layout unit, barycenter association, in-layer successor constraint) the same way it does
     * for any other north/south port with an edge — no injected processor needed.
     *
     * All boundary ports are SOUTH. Multiple attachments share SOUTH; ELK's own crossing
     * minimization and port distribution place them along the host's bottom edge.
     *
     * The sibling node is a size carrier only, for the boundary event's own shape dimensions —
     * it carries no ELK edge and is not part of the routing graph.
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
            addNodeLabel(beNode, be.name)
            nodeMap[be.id] = beNode
        }
    }

    /**
     * Maps sequence flows to ELK edges.
     *
     * A flow whose source is a [BoundaryEvent] is sourced at that event's SOUTH port
     * ([portMap]) rather than at a node — the exception edge exits the host's port directly, so
     * ELK routes and places the handler chain as a real graph consumer. Loop-back edges
     * (back-edges that create cycles in subprocess/participant flows) are emitted with source and
     * target swapped (AD-622-15), so ELK always lays out an acyclic graph; the inverse transform
     * happens at read time in [dev.groknull.bpmner.layout.internal.placement.SequenceEdgeElkCopy].
     *
     * The [reversedFlowIds] set is pre-computed by [map] and passed in to avoid recomputation.
     */
    private fun mapSequenceFlows(
        model: BpmnModelInstance,
        nodeMap: Map<String, ElkNode>,
        portMap: Map<String, ElkPort>,
        edgeMap: MutableMap<String, ElkEdge>,
        reversedFlowIds: Set<String>,
    ) {
        val boundaryIds = model.getModelElementsByType(BoundaryEvent::class.java)
            .mapTo(mutableSetOf()) { it.id }

        model.getModelElementsByType(SequenceFlow::class.java)
            .forEach { sf ->
                val targetId = sf.target?.id
                val source = sequenceFlowSource(sf, boundaryIds, nodeMap, portMap)
                val target = nodeMap[targetId]
                    ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' target '$targetId' not found")
                if (target.parent == null || (source is ElkNode && source.parent == null)) return@forEach
                val elkEdge = if (sf.id in reversedFlowIds) {
                    ElkGraphUtil.createSimpleEdge(target, source)
                } else {
                    ElkGraphUtil.createSimpleEdge(source, target)
                }
                elkEdge.identifier = sf.id
                addEdgeLabel(elkEdge, sf.name)
                edgeMap[sf.id] = elkEdge
            }
    }

    /** Resolves [sf]'s ELK source: its SOUTH port if it starts at a [BoundaryEvent], else its node. */
    private fun sequenceFlowSource(
        sf: SequenceFlow,
        boundaryIds: Set<String>,
        nodeMap: Map<String, ElkNode>,
        portMap: Map<String, ElkPort>,
    ): ElkConnectableShape {
        val sourceId = sf.source?.id
        return if (sourceId in boundaryIds) {
            portMap[sourceId]
                ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' source port '$sourceId' not found")
        } else {
            nodeMap[sourceId]
                ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' source '$sourceId' not found")
        }
    }

    /**
     * Maps collaboration-level edges after their node endpoints have been added to the hierarchy.
     *
     * Any flow whose endpoints sit in different participants is excluded: that participant pair's
     * relative position is always decided afterward by a bounded BPMN exception
     * (`ExternalBlackBoxBandPlacement` for a black box, `WhiteBoxPoolBandPlacement` for stacked
     * white-box pools), which fully regenerates the flow's route rather than projecting ELK's
     * section. Modelling it as a real graph edge anyway buys nothing and actively harms the
     * primary flow: because collaborations use `HIERARCHY_HANDLING.INCLUDE_CHILDREN`, ELK solves
     * the whole collaboration as one joint layered graph, so a cross-participant edge's hierarchical
     * port dummies let its crossing minimisation and network-simplex node placement perturb each
     * participant's own internal layout for a routing decision that is discarded anyway.
     *
     * This exclusion is retained deliberately (AD-622-36): probe `76c9571d` measured that removing
     * it reorders a task ~58px within its own pool, confirming the perturbation. A change proposing
     * to remove it has not read that ADR.
     */
    private fun mapMessageFlows(
        root: ElkNode,
        collaboration: Collaboration,
        nodeMap: Map<String, ElkNode>,
        edgeMap: MutableMap<String, ElkEdge>,
    ) {
        collaboration.messageFlows.forEach { flow ->
            val sourceId = (flow.source as? org.camunda.bpm.model.bpmn.instance.BaseElement)?.id
            val targetId = (flow.target as? org.camunda.bpm.model.bpmn.instance.BaseElement)?.id
            val source = nodeMap[sourceId] ?: return@forEach
            val target = nodeMap[targetId] ?: return@forEach
            if (source.parent == null || target.parent == null) return@forEach
            if (participantIdOf(source, root) != participantIdOf(target, root)) return@forEach
            val elkEdge = ElkGraphUtil.createSimpleEdge(source, target)
            elkEdge.identifier = flow.id
            addEdgeLabel(elkEdge, flow.name)
            edgeMap[flow.id] = elkEdge
        }
    }

    /** Walks [node]'s ELK ancestor chain up to its top-level container (participant or black box). */
    private fun participantIdOf(node: ElkNode, root: ElkNode): String? {
        var current = node
        while (current.parent != null && current.parent != root) {
            current = current.parent
        }
        return current.identifier
    }

    private fun addNodeLabel(node: ElkNode, name: String?) {
        if (name.isNullOrBlank()) return
        if (node.width > 0.0 && node.height > 0.0) {
            node.setProperty(CoreOptions.NODE_SIZE_MINIMUM, KVector(node.width, node.height))
        }
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions(name, BpmnPlacementPass.LABEL_WIDTH)
        ElkGraphUtil.createLabel(node).also { label ->
            label.text = name
            label.width = width
            label.height = height
        }
        node.setProperty(CoreOptions.NODE_LABELS_PLACEMENT, NodeLabelPlacement.outsideBottomCenter())
        node.setProperty(CoreOptions.NODE_SIZE_CONSTRAINTS, EnumSet.of(SizeConstraint.MINIMUM_SIZE))
    }

    private fun addEdgeLabel(edge: ElkEdge, name: String?) {
        if (name.isNullOrBlank()) return
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions(name, BpmnPlacementPass.EDGE_LABEL_WIDTH)
        ElkGraphUtil.createLabel(edge).also { label ->
            label.text = name
            label.width = width
            label.height = height
        }
        edge.setProperty(CoreOptions.EDGE_LABELS_PLACEMENT, EdgeLabelPlacement.CENTER)
    }

    /**
     * Returns the IDs of sequence flows that are back-edges among [flowElements] (i.e. create
     * cycles). [flowElements] is a [SubProcess]'s or a [Participant]'s
     * [org.camunda.bpm.model.bpmn.instance.Process]'s own direct flow elements — a nested
     * SubProcess's flow elements are scanned separately by the caller, not recursively here.
     *
     * A self-referencing flow (source and target the same node) is never included: it is ELK's
     * own self-loop primitive, pulled out of the graph and handled by dedicated processors
     * ([org.eclipse.elk.alg.layered.intermediate.loops]) entirely independently of cycle-breaking
     * — swapping its identical endpoints would be a no-op that leaves a real cycle in the graph
     * AD-622-15 requires to be acyclic by construction.
     *
     * Uses an iterative DFS with an explicit call-stack to avoid a local fun declaration
     * (which would count against the TooManyFunctions detekt limit).
     *
     * Each stack frame is (nodeId, iteratorIndex): when the iterator is exhausted the node is
     * popped from the DFS ancestor-stack and marked fully visited.
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod")
    private fun findLoopBackEdges(flowElements: Collection<FlowElement>): Set<String> {
        val flows = flowElements.filterIsInstance<SequenceFlow>()
        val succFlows = mutableMapOf<String, MutableList<Pair<String, String>>>() // nodeId → [(targetId, flowId)]
        flows.forEach { sf ->
            val s = sf.source?.id ?: return@forEach
            val t = sf.target?.id ?: return@forEach
            if (s == t) return@forEach
            succFlows.getOrPut(s) { mutableListOf() }.add(t to sf.id)
        }

        val startEvents = flowElements.filterIsInstance<StartEvent>()
        val seeds = if (startEvents.isNotEmpty()) {
            startEvents.map { it.id }
        } else {
            flowElements.filterIsInstance<FlowNode>().map { it.id }
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

    /** Applies the fixed, executable ELK layout profile. */
    private fun applyRootLayoutOptions(root: ElkNode) {
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        root.setProperty(CoreOptions.RANDOM_SEED, RANDOM_SEED)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
        applyFlowSpacing(root)
        // Floating nodes (no sequence-flow edge at all — an event subprocess or a
        // compensation-handler task) become their own disconnected component: guaranteed
        // non-overlapping with the main flow (see FloatingElementAnchorProbeTest, AD-622-35).
        root.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, true)
        // NETWORK_SIMPLEX keeps the primary flow on a single Y baseline.
        root.setProperty(LayeredOptions.NODE_PLACEMENT_STRATEGY, NodePlacementStrategy.NETWORK_SIMPLEX)
        // Use model order for deterministic, document-order branch ordering.
        root.setProperty(LayeredOptions.CONSIDER_MODEL_ORDER_STRATEGY, OrderingStrategy.NODES_AND_EDGES)
        // AD-622-35: order the row-packer's components by BPMN declaration order too, so the
        // main flow (declared first) sets row 1's width and a floating element wraps below it,
        // rather than the packer's default size-based ordering (which can float a small
        // component above the main flow — see FloatingElementAnchorProbeTest).
        root.setProperty(
            LayeredOptions.CONSIDER_MODEL_ORDER_COMPONENTS,
            ComponentOrderingStrategy.MODEL_ORDER,
        )
        // Ensure adequate spacing between connected components.
        root.setProperty(LayeredOptions.SPACING_COMPONENT_COMPONENT, NODE_NODE_SPACING)
        // A named edge spanning 2+ layers (e.g. a gateway branch straight to a later end event,
        // skipping an in-between task's layer) gets a virtual label-dummy node in one of the
        // skipped layers. The default (MEDIAN_LAYER) routes the edge through that dummy at the
        // layer nearest the middle, which can force an unrelated extra bend depending on which
        // layer happens to fall in the middle. HEAD_LAYER keeps the dummy in the layer nearest the
        // edge's source, closest to the branching decision, and produces the minimal-bend route.
        root.setProperty(
            LayeredOptions.EDGE_LABELS_CENTER_LABEL_PLACEMENT_STRATEGY,
            CenterEdgeLabelPlacementStrategy.HEAD_LAYER,
        )
    }

    private fun applyParticipantProfile(node: ElkNode) {
        node.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        node.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, false)
        node.setProperty(
            CoreOptions.PADDING,
            ElkPadding(
                PARTICIPANT_CONTENT_PADDING,
                PARTICIPANT_CONTENT_PADDING,
                PARTICIPANT_CONTENT_PADDING,
                PARTICIPANT_HEADER_WIDTH + PARTICIPANT_CONTENT_PADDING,
            ),
        )
        applyCompoundProfile(node)
    }

    /**
     * AD-622-28: `NODE_LABELS_PLACEMENT = outsideBottomCenter` (`:444`) makes a compound's
     * content bbox the node row plus the label strip below it, so symmetric padding centres
     * that combined box rather than the node row — the row ends up sitting half the label
     * height above the band's visual centre. Declaring extra top padding equal to the lane's
     * own direct children's tallest label — rather than translating content after the fact —
     * makes the node row itself land on the band's centre. Not gated on the lane spike: this
     * corrects the same defect whether the band is later ELK-stacked or kept at
     * `CollaborationShapePlacement`'s declared floor.
     */
    private fun applyLaneProfile(node: ElkNode, lane: Lane) {
        node.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.INCLUDE_CHILDREN)
        node.setProperty(CoreOptions.SEPARATE_CONNECTED_COMPONENTS, false)
        val maxLabelHeight = lane.flowNodeRefs
            .mapNotNull { it.name }
            .filter(String::isNotBlank)
            .maxOfOrNull { BpmnPlacementPass.estimateLabelDimensions(it, BpmnPlacementPass.LABEL_WIDTH).second }
            ?: 0.0
        node.setProperty(
            CoreOptions.PADDING,
            ElkPadding(LANE_PADDING + maxLabelHeight, LANE_PADDING, LANE_PADDING, LANE_PADDING),
        )
        applyCompoundProfile(node)
    }

    private fun applyCompoundProfile(node: ElkNode) {
        applyFlowSpacing(node)
    }

    /** Applies the fixed spacing profile to each graph because ELK does not inherit it. */
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
    internal const val SUBPROCESS_PADDING = 50.0
    internal const val BOUNDARY_PORT_SIZE = 10.0

    // Collaboration geometry constants
    internal const val PARTICIPANT_HEADER_WIDTH = 30.0
    internal const val PARTICIPANT_GAP = 80.0
    internal const val BLACK_BOX_WIDTH = 100.0
    internal const val BLACK_BOX_HEIGHT = 60.0
    private const val PARTICIPANT_CONTENT_PADDING = 20.0
    private const val LANE_PADDING = 20.0

    // In-layer spacing (vertical for RIGHT direction).
    private const val NODE_NODE_SPACING = 60.0

    // Between-layer spacing (horizontal for RIGHT direction).
    private const val NODE_NODE_BETWEEN_LAYERS = 90.0

    private const val EDGE_NODE_SPACING = 25.0
    private const val EDGE_NODE_BETWEEN_LAYERS = 25.0
}
