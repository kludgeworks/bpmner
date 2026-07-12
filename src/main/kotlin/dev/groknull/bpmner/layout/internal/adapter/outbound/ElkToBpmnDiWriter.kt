/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
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

/**
 * Writes ELK layout results back into a Camunda [BpmnModelInstance] as BPMN-DI.
 *
 * Creates a single BPMNDiagram/BPMNPlane, then emits BPMNShape for each flow node,
 * text annotation, and group, and BPMNEdge for each sequence flow and association.
 * Association waypoints are derived from the center of the connected shapes because
 * associations are not ELK edges and carry no section data.
 */
internal object ElkToBpmnDiWriter {

    fun write(
        model: BpmnModelInstance,
        nodeMap: Map<String, ElkNode>,
        edgeMap: Map<String, ElkEdge>,
    ) {
        val plane = createDiagramAndPlane(model)
        val shapeById = writeNodeShapes(model, plane, nodeMap)
        writeSequenceEdges(model, plane, edgeMap)
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
    ): Map<String, BpmnShape> {
        val shapeById = mutableMapOf<String, BpmnShape>()
        for (flowNode in model.getModelElementsByType(FlowNode::class.java).sortedBy { it.id }) {
            val elkNode = nodeMap[flowNode.id] ?: continue
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${flowNode.id}"
            shape.bpmnElement = flowNode
            shape.bounds = model.newBounds(elkNode.x, elkNode.y, elkNode.width, elkNode.height)
            if (elkNode.labels.isNotEmpty()) {
                val elkLabel = elkNode.labels.first()
                val bpmnLabel = model.newInstance(BpmnLabel::class.java)
                bpmnLabel.bounds = model.newBounds(
                    elkNode.x + elkLabel.x,
                    elkNode.y + elkLabel.y,
                    elkLabel.width,
                    elkLabel.height,
                )
                shape.bpmnLabel = bpmnLabel
            }
            plane.addChildElement(shape)
            shapeById[flowNode.id] = shape
        }
        for (ann in model.getModelElementsByType(TextAnnotation::class.java).sortedBy { it.id }) {
            val elkNode = nodeMap[ann.id] ?: continue
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${ann.id}"
            shape.bpmnElement = ann
            shape.bounds = model.newBounds(elkNode.x, elkNode.y, elkNode.width, elkNode.height)
            plane.addChildElement(shape)
            shapeById[ann.id] = shape
        }
        for (group in model.getModelElementsByType(Group::class.java).sortedBy { it.id }) {
            val elkNode = nodeMap[group.id] ?: continue
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${group.id}"
            shape.bpmnElement = group
            shape.bounds = model.newBounds(elkNode.x, elkNode.y, elkNode.width, elkNode.height)
            plane.addChildElement(shape)
            shapeById[group.id] = shape
        }
        return shapeById
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
                val offsetX = elkEdge.sections.firstOrNull()?.startX ?: 0.0
                val offsetY = elkEdge.sections.firstOrNull()?.startY ?: 0.0
                val bpmnLabel = model.newInstance(BpmnLabel::class.java)
                bpmnLabel.bounds = model.newBounds(
                    offsetX + elkLabel.x,
                    offsetY + elkLabel.y,
                    elkLabel.width,
                    elkLabel.height,
                )
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
            val bpmnEdge = model.newInstance(BpmnEdge::class.java)
            bpmnEdge.id = "BPMNEdge_${assoc.id}"
            bpmnEdge.bpmnElement = assoc
            val (s1x, s1y) = shapeById[assoc.source?.id]?.let { shapeCenter(it) } ?: Pair(0.0, 0.0)
            val (s2x, s2y) = shapeById[assoc.target?.id]?.let { shapeCenter(it) } ?: Pair(0.0, 0.0)
            model.newWaypoint(s1x, s1y).also { bpmnEdge.waypoints.add(it) }
            model.newWaypoint(s2x, s2y).also { bpmnEdge.waypoints.add(it) }
            plane.addChildElement(bpmnEdge)
        }
    }

    private fun writeWaypoints(model: BpmnModelInstance, bpmnEdge: BpmnEdge, elkEdge: ElkEdge) {
        val section = elkEdge.sections.firstOrNull()
        if (section == null) {
            // No section means ELK produced no routing; emit two zero-coordinate waypoints
            // so the edge has the minimum valid DI rather than being omitted entirely.
            model.newWaypoint(0.0, 0.0).also { bpmnEdge.waypoints.add(it) }
            model.newWaypoint(0.0, 0.0).also { bpmnEdge.waypoints.add(it) }
            return
        }
        model.newWaypoint(section.startX, section.startY).also { bpmnEdge.waypoints.add(it) }
        for (bend in section.bendPoints) {
            model.newWaypoint(bend.x, bend.y).also { bpmnEdge.waypoints.add(it) }
        }
        model.newWaypoint(section.endX, section.endY).also { bpmnEdge.waypoints.add(it) }
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
