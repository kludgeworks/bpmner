/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.eclipse.elk.alg.layered.options.LayeredOptions
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.core.options.Direction
import org.eclipse.elk.core.options.EdgeRouting
import org.eclipse.elk.core.options.HierarchyHandling
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.util.ElkGraphUtil

/**
 * Maps a Camunda [BpmnModelInstance] to an ELK graph ready for layout.
 *
 * Flat scope: flow nodes, sequence flows, text annotations, and groups.
 * Containment (subprocesses) and collaboration (pools/lanes) are handled by later stages.
 * Sequence flows with missing source or target are an input-validity failure; they throw
 * [BpmnAutoLayoutException] rather than silently producing an incomplete graph.
 *
 * Identity maps keyed by BPMN element ID are returned so the DI writer can correlate
 * ELK geometry back to the originating BPMN elements.
 */
internal object BpmnToElkMapper {

    internal data class ElkGraphResult(
        val root: ElkNode,
        val nodeMap: Map<String, ElkNode>,
        val edgeMap: Map<String, ElkEdge>,
    )

    fun map(model: BpmnModelInstance): ElkGraphResult {
        val root = ElkGraphUtil.createGraph()
        applyLayoutOptions(root)
        val nodeMap = mutableMapOf<String, ElkNode>()
        val edgeMap = mutableMapOf<String, ElkEdge>()
        mapFlowNodes(model, root, nodeMap)
        mapSequenceFlows(model, nodeMap, edgeMap)
        mapAnnotations(model, root, nodeMap)
        mapGroups(model, root, nodeMap)
        return ElkGraphResult(root, nodeMap, edgeMap)
    }

    private fun applyLayoutOptions(root: ElkNode) {
        root.setProperty(CoreOptions.ALGORITHM, LayeredOptions.ALGORITHM_ID)
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT)
        root.setProperty(CoreOptions.EDGE_ROUTING, EdgeRouting.ORTHOGONAL)
        root.setProperty(CoreOptions.RANDOM_SEED, RANDOM_SEED)
        root.setProperty(CoreOptions.HIERARCHY_HANDLING, HierarchyHandling.SEPARATE_CHILDREN)
        root.setProperty(CoreOptions.SPACING_NODE_NODE, NODE_NODE_SPACING)
        root.setProperty(CoreOptions.SPACING_EDGE_NODE, EDGE_NODE_SPACING)
    }

    private fun mapFlowNodes(
        model: BpmnModelInstance,
        root: ElkNode,
        nodeMap: MutableMap<String, ElkNode>,
    ) {
        for (flowNode in model.getModelElementsByType(FlowNode::class.java).sortedBy { it.id }) {
            val elkNode = ElkGraphUtil.createNode(root)
            elkNode.identifier = flowNode.id
            val (w, h) = nodeDimensions(flowNode)
            elkNode.width = w
            elkNode.height = h
            flowNode.name?.takeIf { it.isNotBlank() }?.let { label ->
                val elkLabel = ElkGraphUtil.createLabel(label, elkNode)
                val (lw, lh) = labelDimensions(label)
                elkLabel.width = lw
                elkLabel.height = lh
            }
            nodeMap[flowNode.id] = elkNode
        }
    }

    private fun mapSequenceFlows(
        model: BpmnModelInstance,
        nodeMap: Map<String, ElkNode>,
        edgeMap: MutableMap<String, ElkEdge>,
    ) {
        for (sf in model.getModelElementsByType(SequenceFlow::class.java).sortedBy { it.id }) {
            val sourceNode = nodeMap[sf.source?.id]
                ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' source '${sf.source?.id}' not found")
            val targetNode = nodeMap[sf.target?.id]
                ?: throw BpmnAutoLayoutException("ELK layout: flow '${sf.id}' target '${sf.target?.id}' not found")
            val elkEdge = ElkGraphUtil.createSimpleEdge(sourceNode, targetNode)
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

    private const val GLYPH_WIDTH = 7.0
    private const val LABEL_MIN_WIDTH = 20.0
    private const val LABEL_HEIGHT = 14.0

    private const val NODE_NODE_SPACING = 30.0
    private const val EDGE_NODE_SPACING = 20.0
}
