/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnToElkMapper.ElkGraphResult
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnDiagram
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnLabel
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnPlane
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape
import org.camunda.bpm.model.bpmn.instance.dc.Bounds
import org.camunda.bpm.model.bpmn.instance.di.Waypoint
import org.eclipse.elk.graph.ElkEdge
import org.eclipse.elk.graph.ElkNode
import org.eclipse.elk.graph.ElkPort

/**
 * Writes ELK layout results back into a Camunda [BpmnModelInstance] as BPMN-DI.
 *
 * All [BPMNShape] bounds are written through [absolutePosition], which accumulates
 * parent ELK node offsets so nested nodes receive correct absolute coordinates.
 * This is the single coordinate-translation path: no shape bounds are written from
 * raw [ElkNode.x]/[ElkNode.y] values.
 *
 * Boundary event shapes are positioned at the host's perimeter using the port offset
 * returned by ELK, also via [absolutePosition].
 */
@Suppress("TooManyFunctions")
internal object ElkToBpmnDiWriter {

    fun write(model: BpmnModelInstance, result: ElkGraphResult) {
        val plane = createDiagramAndPlane(model)
        val shapeById = writeNodeShapes(model, plane, result.nodeMap, result.portMap)
        writeSequenceEdges(model, plane, result.edgeMap)
        writeAssociationEdges(model, plane, shapeById)
    }

    private fun createDiagramAndPlane(model: BpmnModelInstance): BpmnPlane {
        val diagram = model.newInstance(BpmnDiagram::class.java)
        diagram.id = "BPMNDiagram_1"
        val plane = model.newInstance(BpmnPlane::class.java)
        plane.id = "BPMNPlane_1"
        model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process::class.java)
            .firstOrNull()?.let { plane.bpmnElement = it }
        diagram.bpmnPlane = plane
        model.definitions.addChildElement(diagram)
        return plane
    }

    private fun writeNodeShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        nodeMap: Map<String, ElkNode>,
        portMap: Map<String, ElkPort>,
    ): Map<String, BpmnShape> {
        val shapeById = mutableMapOf<String, BpmnShape>()
        writeFlowNodeShapes(model, plane, nodeMap, shapeById)
        writeBoundaryShapes(model, plane, nodeMap, portMap, shapeById)
        writeArtifactShapes(model, plane, nodeMap, shapeById)
        return shapeById
    }

    /** Writes BPMNShape for all non-boundary FlowNodes (tasks, events, gateways, subprocesses). */
    private fun writeFlowNodeShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        nodeMap: Map<String, ElkNode>,
        shapeById: MutableMap<String, BpmnShape>,
    ) {
        for (flowNode in model.getModelElementsByType(FlowNode::class.java)
            .filter { it !is BoundaryEvent }
            .sortedBy { it.id }) {
            val elkNode = nodeMap[flowNode.id] ?: continue
            val (ax, ay) = absolutePosition(elkNode)
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${flowNode.id}"
            shape.bpmnElement = flowNode
            shape.bounds = model.newBounds(ax, ay, elkNode.width, elkNode.height)
            if (flowNode is SubProcess) shape.isExpanded = true
            addLabelIfPresent(model, shape, elkNode, ax, ay)
            plane.addChildElement(shape)
            shapeById[flowNode.id] = shape
        }
    }

    /** Writes BPMNShape for each BoundaryEvent, positioned at host perimeter via port offset. */
    private fun writeBoundaryShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        nodeMap: Map<String, ElkNode>,
        portMap: Map<String, ElkPort>,
        shapeById: MutableMap<String, BpmnShape>,
    ) {
        for (be in model.getModelElementsByType(BoundaryEvent::class.java).sortedBy { it.id }) {
            val beNode = nodeMap[be.id] ?: continue
            val (ax, ay) = boundaryAbsolutePosition(be, beNode, nodeMap, portMap)
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${be.id}"
            shape.bpmnElement = be
            shape.bounds = model.newBounds(ax, ay, beNode.width, beNode.height)
            addLabelIfPresent(model, shape, beNode, ax, ay)
            plane.addChildElement(shape)
            shapeById[be.id] = shape
        }
    }

    /** Writes BPMNShape for TextAnnotations and Groups (artifacts, not FlowNodes). */
    private fun writeArtifactShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        nodeMap: Map<String, ElkNode>,
        shapeById: MutableMap<String, BpmnShape>,
    ) {
        for (ann in model.getModelElementsByType(TextAnnotation::class.java).sortedBy { it.id }) {
            val elkNode = nodeMap[ann.id] ?: continue
            val (ax, ay) = absolutePosition(elkNode)
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${ann.id}"
            shape.bpmnElement = ann
            shape.bounds = model.newBounds(ax, ay, elkNode.width, elkNode.height)
            plane.addChildElement(shape)
            shapeById[ann.id] = shape
        }
        for (group in model.getModelElementsByType(Group::class.java).sortedBy { it.id }) {
            val elkNode = nodeMap[group.id] ?: continue
            val (ax, ay) = absolutePosition(elkNode)
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${group.id}"
            shape.bpmnElement = group
            shape.bounds = model.newBounds(ax, ay, elkNode.width, elkNode.height)
            plane.addChildElement(shape)
            shapeById[group.id] = shape
        }
    }

    private fun addLabelIfPresent(
        model: BpmnModelInstance,
        shape: BpmnShape,
        elkNode: ElkNode,
        ax: Double,
        ay: Double,
    ) {
        if (elkNode.labels.isNotEmpty()) {
            val elkLabel = elkNode.labels.first()
            val bpmnLabel = model.newInstance(BpmnLabel::class.java)
            bpmnLabel.bounds = model.newBounds(ax + elkLabel.x, ay + elkLabel.y, elkLabel.width, elkLabel.height)
            shape.bpmnLabel = bpmnLabel
        }
    }

    private fun boundaryAbsolutePosition(
        be: BoundaryEvent,
        beNode: ElkNode,
        nodeMap: Map<String, ElkNode>,
        portMap: Map<String, ElkPort>,
    ): Pair<Double, Double> {
        val port = portMap[be.id]
        val hostNode = be.attachedTo?.id?.let { nodeMap[it] }
        return if (port != null && hostNode != null) {
            val (hx, hy) = absolutePosition(hostNode)
            Pair(
                hx + port.x + port.width / 2.0 - beNode.width / 2.0,
                hy + port.y + port.height / 2.0 - beNode.height / 2.0,
            )
        } else {
            absolutePosition(beNode)
        }
    }

    private fun writeSequenceEdges(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        edgeMap: Map<String, ElkEdge>,
    ) {
        for (sf in model.getModelElementsByType(SequenceFlow::class.java).sortedBy { it.id }) {
            val elkEdge = edgeMap[sf.id] ?: continue
            val bpmnEdge = model.newInstance(BpmnEdge::class.java)
            bpmnEdge.id = "BPMNEdge_${sf.id}"
            bpmnEdge.bpmnElement = sf
            writeWaypoints(model, bpmnEdge, elkEdge)
            if (elkEdge.labels.isNotEmpty() && !sf.name.isNullOrBlank()) {
                val elkLabel = elkEdge.labels.first()
                val bpmnLabel = model.newInstance(BpmnLabel::class.java)
                // ELK reports label coordinates in absolute root-graph space.
                bpmnLabel.bounds = model.newBounds(elkLabel.x, elkLabel.y, elkLabel.width, elkLabel.height)
                bpmnEdge.bpmnLabel = bpmnLabel
            }
            plane.addChildElement(bpmnEdge)
        }
    }

    private fun writeAssociationEdges(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        shapeById: Map<String, BpmnShape>,
    ) {
        for (assoc in model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Association::class.java)
            .sortedBy { it.id }) {
            val sourceShape = shapeById[assoc.source?.id]
            val targetShape = shapeById[assoc.target?.id]
            if (sourceShape == null || targetShape == null) continue
            val bpmnEdge = model.newInstance(BpmnEdge::class.java)
            bpmnEdge.id = "BPMNEdge_${assoc.id}"
            bpmnEdge.bpmnElement = assoc
            val (s1x, s1y) = shapeCenter(sourceShape)
            val (s2x, s2y) = shapeCenter(targetShape)
            model.newWaypoint(s1x, s1y).also { bpmnEdge.waypoints.add(it) }
            model.newWaypoint(s2x, s2y).also { bpmnEdge.waypoints.add(it) }
            plane.addChildElement(bpmnEdge)
        }
    }

    private fun writeWaypoints(model: BpmnModelInstance, bpmnEdge: BpmnEdge, elkEdge: ElkEdge) {
        val section = elkEdge.sections.firstOrNull()
            ?: throw BpmnAutoLayoutException(
                "ELK layout: edge '${elkEdge.identifier}' has no routing section — " +
                    "ELK did not produce waypoints for this edge",
            )
        model.newWaypoint(section.startX, section.startY).also { bpmnEdge.waypoints.add(it) }
        for (bend in section.bendPoints) {
            model.newWaypoint(bend.x, bend.y).also { bpmnEdge.waypoints.add(it) }
        }
        model.newWaypoint(section.endX, section.endY).also { bpmnEdge.waypoints.add(it) }
    }

    /**
     * Accumulates parent ELK node offsets to convert a node's ELK-relative coordinates
     * into absolute canvas coordinates for BPMN-DI.
     *
     * ELK returns child coordinates relative to the parent compound node. The root graph
     * node has a null identifier; the walk stops there.
     */
    internal fun absolutePosition(node: ElkNode): Pair<Double, Double> {
        var x = node.x
        var y = node.y
        var parent = node.parent
        while (parent != null && parent.identifier != null) {
            x += parent.x
            y += parent.y
            parent = parent.parent
        }
        return x to y
    }

    private fun shapeCenter(shape: BpmnShape): Pair<Double, Double> {
        val b = shape.bounds ?: return Pair(0.0, 0.0)
        return Pair(b.x + b.width / 2.0, b.y + b.height / 2.0)
    }

    private fun BpmnModelInstance.newBounds(x: Double, y: Double, w: Double, h: Double): Bounds {
        val b = newInstance(Bounds::class.java)
        b.x = x
        b.y = y
        b.width = w
        b.height = h
        return b
    }

    private fun BpmnModelInstance.newWaypoint(x: Double, y: Double): Waypoint {
        val wp = newInstance(Waypoint::class.java)
        wp.x = x
        wp.y = y
        return wp
    }
}
