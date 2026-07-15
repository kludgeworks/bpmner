/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.PlacedLayout
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

/**
 * Writes [PlacedLayout] geometry into a Camunda [BpmnModelInstance] as BPMN-DI.
 *
 * This is a mechanical serialisation step: all geometry (shape bounds, label bounds,
 * edge waypoints) comes pre-computed from [BpmnPlacementPass]. The writer makes no
 * placement decisions — it only translates [PlacedLayout] into Camunda DI model objects.
 *
 * The writer never copies an element's own coordinates onto a label — labels come
 * exclusively from [PlacedLayout.labels].
 */
internal object ElkToBpmnDiWriter {

    fun write(model: BpmnModelInstance, layout: PlacedLayout) {
        val plane = createDiagramAndPlane(model)
        writeFlowNodeShapes(model, plane, layout)
        writeBoundaryShapes(model, plane, layout)
        writeArtifactShapes(model, plane, layout)
        writeSequenceEdges(model, plane, layout)
        writeAssociationEdges(model, plane, layout)
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

    /** Writes BPMNShape for all non-boundary FlowNodes using geometry from [PlacedLayout.shapes]. */
    private fun writeFlowNodeShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
    ) {
        for (flowNode in model.getModelElementsByType(FlowNode::class.java)
            .filter { it !is BoundaryEvent }
            .sortedBy { it.id }) {
            val rect = layout.shapes[flowNode.id] ?: continue
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${flowNode.id}"
            shape.bpmnElement = flowNode
            shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
            if (flowNode is SubProcess) shape.isExpanded = flowNode.id in layout.expanded
            writeLabelIfPresent(model, shape, flowNode.id, layout)
            plane.addChildElement(shape)
        }
    }

    /** Writes BPMNShape for BoundaryEvents using geometry from [PlacedLayout.shapes]. */
    private fun writeBoundaryShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
    ) {
        for (be in model.getModelElementsByType(BoundaryEvent::class.java).sortedBy { it.id }) {
            val rect = layout.shapes[be.id] ?: continue
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${be.id}"
            shape.bpmnElement = be
            shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
            writeLabelIfPresent(model, shape, be.id, layout)
            plane.addChildElement(shape)
        }
    }

    /** Writes BPMNShape for TextAnnotations and Groups using geometry from [PlacedLayout.shapes]. */
    private fun writeArtifactShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
    ) {
        for (ann in model.getModelElementsByType(TextAnnotation::class.java).sortedBy { it.id }) {
            val rect = layout.shapes[ann.id] ?: continue
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${ann.id}"
            shape.bpmnElement = ann
            shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
            plane.addChildElement(shape)
        }
        for (group in model.getModelElementsByType(Group::class.java).sortedBy { it.id }) {
            val rect = layout.shapes[group.id] ?: continue
            val shape = model.newInstance(BpmnShape::class.java)
            shape.id = "BPMNShape_${group.id}"
            shape.bpmnElement = group
            shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
            plane.addChildElement(shape)
        }
    }

    /**
     * Writes a [BpmnLabel] from [PlacedLayout.labels] if the element has a label.
     * Never copies the element's own coordinates — labels come exclusively from
     * [PlacedLayout.labels] (fixes the label-collision defect from BLOCK-557-3).
     */
    private fun writeLabelIfPresent(
        model: BpmnModelInstance,
        shape: BpmnShape,
        ownerId: String,
        layout: PlacedLayout,
    ) {
        val labelRect = layout.labels[ownerId] ?: return
        val bpmnLabel = model.newInstance(BpmnLabel::class.java)
        bpmnLabel.id = "BPMNLabel_$ownerId"
        bpmnLabel.bounds = model.newBounds(labelRect.x, labelRect.y, labelRect.w, labelRect.h)
        shape.bpmnLabel = bpmnLabel
    }

    private fun writeSequenceEdges(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
    ) {
        for (sf in model.getModelElementsByType(SequenceFlow::class.java).sortedBy { it.id }) {
            val waypoints = layout.edges[sf.id]
            if (waypoints.isNullOrEmpty()) {
                throw BpmnAutoLayoutException(
                    "ELK layout: sequence flow '${sf.id}' has no waypoints in PlacedLayout — " +
                        "ELK did not produce routing for this edge",
                )
            }
            val bpmnEdge = model.newInstance(BpmnEdge::class.java)
            bpmnEdge.id = "BPMNEdge_${sf.id}"
            bpmnEdge.bpmnElement = sf
            for ((index, waypoint) in waypoints.withIndex()) {
                if (index > 0 && waypoint == waypoints[index - 1]) continue
                model.newWaypoint(waypoint.x, waypoint.y).also { bpmnEdge.waypoints.add(it) }
            }
            // Edge label from PlacedLayout (never from ELK label coordinates)
            layout.labels[sf.id]?.let { labelRect ->
                val bpmnLabel = model.newInstance(BpmnLabel::class.java)
                bpmnLabel.id = "BPMNLabel_${sf.id}"
                bpmnLabel.bounds = model.newBounds(labelRect.x, labelRect.y, labelRect.w, labelRect.h)
                bpmnEdge.bpmnLabel = bpmnLabel
            }
            plane.addChildElement(bpmnEdge)
        }
    }

    private fun writeAssociationEdges(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
    ) {
        for (assoc in model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Association::class.java)
            .sortedBy { it.id }) {
            val waypoints = layout.edges[assoc.id] ?: continue
            val bpmnEdge = model.newInstance(BpmnEdge::class.java)
            bpmnEdge.id = "BPMNEdge_${assoc.id}"
            bpmnEdge.bpmnElement = assoc
            for ((index, waypoint) in waypoints.withIndex()) {
                if (index > 0 && waypoint == waypoints[index - 1]) continue
                model.newWaypoint(waypoint.x, waypoint.y).also { bpmnEdge.waypoints.add(it) }
            }
            plane.addChildElement(bpmnEdge)
        }
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
