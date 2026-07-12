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
 * Maps a Camunda [BpmnModelInstance] to an ELK graph ready for layout.
 *
 * Runs three ordered passes over the model:
 * 1. [mapProcess] — recursive; builds compound nodes for subprocesses, flat nodes for
 *    everything else. BoundaryEvents are skipped here.
 * 2. [mapBoundaryEvents] — creates a port on each host node and a sibling node for each
 *    boundary event. Requires pass 1 to have populated [nodeMap] first.
 * 3. [mapSequenceFlows] — creates ELK edges; checks [portMap] before [nodeMap] for sources
 *    so exception flows route from the boundary port. Requires passes 1 and 2.
 *
 * The result is an [ElkGraphResult] carrying all identity maps needed by [ElkToBpmnDiWriter].
 */
internal object BpmnToElkMapper {

    internal data class ElkGraphResult(
        val root: ElkNode,
        val nodeMap: Map<String, ElkNode>,
        val portMap: Map<String, ElkPort>,
        val edgeMap: Map<String, ElkEdge>,
    )

    fun map(model: BpmnModelInstance): ElkGraphResult {
        val root = ElkGraphUtil.createGraph()
        applyRootLayoutOptions(root)
        val nodeMap = mutableMapOf<String, ElkNode>()
        val portMap = mutableMapOf<String, ElkPort>()
        val edgeMap = mutableMapOf<String, ElkEdge>()

        // Pass 1: build node tree (recursive for subprocesses).
        // FlowElements covers FlowNodes (tasks, events, gateways) and SubProcess.
        // TextAnnotation and Group are Artifacts, not FlowElements — handled separately below.
        val topLevelElements = model.getModelElementsByType(FlowElement::class.java)
            .filter { it.parentElement is org.camunda.bpm.model.bpmn.instance.Process }
            .sortedBy { it.id }
        mapProcess(root, topLevelElements, nodeMap, model)

        // Artifacts (TextAnnotation, Group) are not FlowElements; query them globally.
        mapAnnotations(model, root, nodeMap)
        mapGroups(model, root, nodeMap)

        // Pass 2: boundary events (need host nodes from pass 1)
        mapBoundaryEvents(model, nodeMap, portMap)

        // Pass 3: sequence flows (need both nodes and ports)
        mapSequenceFlows(model, nodeMap, portMap, edgeMap)

        return ElkGraphResult(root, nodeMap, portMap, edgeMap)
    }

    /**
     * Recursively maps flow elements into the given [container].
     * SubProcesses become compound ELK nodes and recurse; BoundaryEvents are skipped
     * (handled in pass 2); other FlowNodes become flat leaf nodes.
     * TextAnnotations and Groups are added as sidecar nodes in [container].
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
                    element.name?.takeIf { it.isNotBlank() }?.let { label ->
                        val elkLabel = ElkGraphUtil.createLabel(label, compound)
                        val (lw, lh) = labelDimensions(label)
                        elkLabel.width = lw
                        elkLabel.height = lh
                    }
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
                    element.name?.takeIf { it.isNotBlank() }?.let { label ->
                        val elkLabel = ElkGraphUtil.createLabel(label, elkNode)
                        val (lw, lh) = labelDimensions(label)
                        elkLabel.width = lw
                        elkLabel.height = lh
                    }
                    nodeMap[element.id] = elkNode
                }

                else -> Unit // TextAnnotation/Group are Artifacts, not FlowElements; handled separately
            }
        }
    }

    private fun mapAnnotations(
        model: BpmnModelInstance,
        root: ElkNode,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (ann in model.getModelElementsByType(TextAnnotation::class.java).sortedBy { it.id }) {
            val elkNode = ElkGraphUtil.createNode(root)
            elkNode.identifier = ann.id
            elkNode.width = ANNOTATION_WIDTH
            elkNode.height = ANNOTATION_HEIGHT
            ann.text?.textContent?.takeIf { it.isNotBlank() }?.let { labelText ->
                val lbl = ElkGraphUtil.createLabel(labelText, elkNode)
                val (lw, lh) = labelDimensions(labelText)
                lbl.width = lw
                lbl.height = lh
            }
            nodeMap[ann.id] = elkNode
        }
    }

    private fun mapGroups(
        model: BpmnModelInstance,
        root: ElkNode,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (group in model.getModelElementsByType(Group::class.java).sortedBy { it.id }) {
            val elkNode = ElkGraphUtil.createNode(root)
            elkNode.identifier = group.id
            elkNode.width = GROUP_WIDTH
            elkNode.height = GROUP_HEIGHT
            nodeMap[group.id] = elkNode
        }
    }

    /**
     * Pass 2: for each [BoundaryEvent], create a port on the host node and a sibling node
     * in the host's container. The port anchors the exception edge; the sibling node
     * receives its own DI shape from [ElkToBpmnDiWriter].
     */
    @Suppress("ThrowsCount") // three distinct precondition failures: no host ref, host absent, host has no parent
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

            // Port on the host: side is SOUTH by default, rotated for additional attachments
            val existingPorts = hostNode.ports.toList()
            val portSide = assignBoundarySide(existingPorts)
            val port = ElkGraphUtil.createPort(hostNode)
            port.identifier = "port_${be.id}"
            port.width = BOUNDARY_PORT_SIZE
            port.height = BOUNDARY_PORT_SIZE
            port.setProperty(CoreOptions.PORT_CONSTRAINTS, PortConstraints.FIXED_SIDE)
            port.setProperty(CoreOptions.PORT_SIDE, portSide)
            portMap[be.id] = port

            // Sibling node in the host's container for DI shape placement
            val container = hostNode.parent ?: throw BpmnAutoLayoutException(
                "ELK layout: host node '$hostId' has no parent container",
            )
            val beNode = ElkGraphUtil.createNode(container)
            beNode.identifier = be.id
            beNode.width = EVENT_SIZE
            beNode.height = EVENT_SIZE
            be.name?.takeIf { it.isNotBlank() }?.let { label ->
                val elkLabel = ElkGraphUtil.createLabel(label, beNode)
                val (lw, lh) = labelDimensions(label)
                elkLabel.width = lw
                elkLabel.height = lh
            }
            nodeMap[be.id] = beNode
        }
    }

    /**
     * Pass 3: map sequence flows to ELK edges. For boundary event sources, uses the port
     * (from [portMap]) as the source so ELK routes from the attachment point. For all other
     * sources uses the node from [nodeMap]. [ElkGraphUtil.createSimpleEdge] computes the
     * LCA container automatically.
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

            val elkEdge = ElkGraphUtil.createSimpleEdge(source, target)
            elkEdge.identifier = sf.id
            sf.name?.takeIf { it.isNotBlank() }?.let { edgeLabel ->
                val lbl = ElkGraphUtil.createLabel(edgeLabel, elkEdge)
                val (lw, lh) = labelDimensions(edgeLabel)
                lbl.width = lw
                lbl.height = lh
            }
            edgeMap[sf.id] = elkEdge
        }
    }

    /**
     * Assigns a deterministic side for a new boundary event port on a host node.
     * First attachment: SOUTH. Additional attachments cycle EAST → NORTH → WEST.
     */
    private fun assignBoundarySide(existingPorts: List<ElkPort>): PortSide {
        val usedSides = existingPorts.mapNotNull { it.getProperty(CoreOptions.PORT_SIDE) }.toSet()
        return listOf(PortSide.SOUTH, PortSide.EAST, PortSide.NORTH, PortSide.WEST)
            .firstOrNull { it !in usedSides } ?: PortSide.SOUTH
    }

    private fun applyRootLayoutOptions(root: ElkNode) {
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        root.setProperty(CoreOptions.RANDOM_SEED, RANDOM_SEED)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
        root.setProperty(CoreOptions.SPACING_NODE_NODE, NODE_NODE_SPACING)
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_NODE_SPACING)
    }

    private fun nodeDimensions(flowNode: FlowNode): Pair<Double, Double> {
        val typeName = flowNode.elementType.typeName
        return when {
            typeName.contains("Event") -> Pair(EVENT_SIZE, EVENT_SIZE)
            typeName.contains("Gateway") -> Pair(GATEWAY_SIZE, GATEWAY_SIZE)
            else -> Pair(TASK_WIDTH, TASK_HEIGHT)
        }
    }

    private fun labelDimensions(text: String): Pair<Double, Double> {
        val width = (text.length * GLYPH_WIDTH).coerceAtLeast(LABEL_MIN_WIDTH)
        return Pair(width, LABEL_HEIGHT)
    }

    private const val RANDOM_SEED = 1

    private const val TASK_WIDTH = 100.0
    private const val TASK_HEIGHT = 80.0
    private const val EVENT_SIZE = 36.0
    private const val GATEWAY_SIZE = 50.0
    private const val ANNOTATION_WIDTH = 100.0
    private const val ANNOTATION_HEIGHT = 60.0
    private const val GROUP_WIDTH = 300.0
    private const val GROUP_HEIGHT = 200.0
    internal const val SUBPROCESS_PADDING = 50.0
    internal const val BOUNDARY_PORT_SIZE = 10.0

    private const val GLYPH_WIDTH = 7.0
    private const val LABEL_MIN_WIDTH = 20.0
    private const val LABEL_HEIGHT = 14.0

    private const val NODE_NODE_SPACING = 30.0
    private const val EDGE_NODE_SPACING = 20.0
}
