/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.PlacedLayout
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent
import org.camunda.bpm.model.bpmn.instance.Collaboration
import org.camunda.bpm.model.bpmn.instance.FlowNode
import org.camunda.bpm.model.bpmn.instance.Group
import org.camunda.bpm.model.bpmn.instance.Lane
import org.camunda.bpm.model.bpmn.instance.MessageFlow
import org.camunda.bpm.model.bpmn.instance.Participant
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
// write(), captureExisting{Shapes,Edges}, plus one writer per DI element type (participant, lane, flowNode,
// boundary, artifact, sequence, message, association edges). Each writer is a distinct serialisation
// concern; splitting into multiple objects would require passing model, plane and layout into each, or
// grouping them under a shared context that adds indirection without reducing the function count.
@Suppress("TooManyFunctions")
internal object ElkToBpmnDiWriter {

    fun write(
        model: BpmnModelInstance,
        layout: PlacedLayout,
        existingShapes: Map<String, BpmnShape> = emptyMap(),
        existingEdges: Map<String, BpmnEdge> = emptyMap(),
    ) {
        val plane = createDiagramAndPlane(model)
        writeParticipantShapes(model, plane, layout, existingShapes)
        writeLaneShapes(model, plane, layout, existingShapes)
        writeFlowNodeShapes(model, plane, layout, existingShapes)
        writeBoundaryShapes(model, plane, layout, existingShapes)
        writeArtifactShapes(model, plane, layout)
        writeSequenceEdges(model, plane, layout, existingEdges)
        writeMessageEdges(model, plane, layout, existingEdges)
        writeAssociationEdges(model, plane, layout, existingEdges)
    }

    /**
     * Captures existing BPMNShape elements indexed by their bpmnElement ID.
     *
     * Must be called BEFORE [ElkBpmnLayouter.removeExistingDi] strips the diagram, so the
     * captured elements still have their full attribute set (bioc: colours, custom extensions).
     * The captured elements are passed into [write] and re-attached to the new plane, preserving
     * all non-geometry attributes while only updating bounds.
     */
    fun captureExistingShapes(model: BpmnModelInstance): Map<String, BpmnShape> =
        model.getModelElementsByType(BpmnShape::class.java)
            .mapNotNull { shape ->
                val id = (shape.bpmnElement as? org.camunda.bpm.model.bpmn.instance.BaseElement)?.id
                    ?: return@mapNotNull null
                id to shape
            }.toMap()

    /**
     * Captures existing BPMNEdge elements indexed by their bpmnElement ID.
     * Must be called BEFORE [ElkBpmnLayouter.removeExistingDi] strips the diagram.
     */
    fun captureExistingEdges(model: BpmnModelInstance): Map<String, BpmnEdge> =
        model.getModelElementsByType(BpmnEdge::class.java)
            .mapNotNull { edge ->
                val id = (edge.bpmnElement as? org.camunda.bpm.model.bpmn.instance.BaseElement)?.id
                    ?: return@mapNotNull null
                id to edge
            }.toMap()

    private fun createDiagramAndPlane(model: BpmnModelInstance): BpmnPlane {
        val diagram = model.newInstance(BpmnDiagram::class.java)
        diagram.id = "BPMNDiagram_1"
        val plane = model.newInstance(BpmnPlane::class.java)
        plane.id = "BPMNPlane_1"
        // Per BPMN 2.0 spec: bpmnElement of BPMNPlane shall be a Collaboration or a Process.
        val collaboration = model.getModelElementsByType(Collaboration::class.java).firstOrNull()
        plane.bpmnElement = collaboration
            ?: model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process::class.java).firstOrNull()
        diagram.bpmnPlane = plane
        model.definitions.addChildElement(diagram)
        return plane
    }

    /** BPMN DI requires isHorizontal=true on BPMNShape for Participant elements. */
    private fun writeParticipantShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
        existingShapes: Map<String, BpmnShape>,
    ) {
        for (participant in model.getModelElementsByType(Participant::class.java).sortedBy { it.id }) {
            val rect = layout.shapes[participant.id] ?: continue
            existingShapes[participant.id]?.also { existing ->
                existing.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                existing.isHorizontal = true
                plane.addChildElement(existing)
            } ?: model.newInstance(BpmnShape::class.java).also { shape ->
                shape.id = "BPMNShape_${participant.id}"
                shape.bpmnElement = participant
                shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                shape.isHorizontal = true
                writeLabelIfPresent(model, shape, participant.id, layout)
                plane.addChildElement(shape)
            }
        }
    }

    /**
     * Writes BPMNShape for [Lane] elements with isHorizontal=true.
     */
    private fun writeLaneShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
        existingShapes: Map<String, BpmnShape>,
    ) {
        for (lane in model.getModelElementsByType(Lane::class.java).sortedBy { it.id }) {
            val rect = layout.shapes[lane.id] ?: continue
            existingShapes[lane.id]?.also { existing ->
                existing.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                existing.isHorizontal = true
                plane.addChildElement(existing)
            } ?: model.newInstance(BpmnShape::class.java).also { shape ->
                shape.id = "BPMNShape_${lane.id}"
                shape.bpmnElement = lane
                shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                shape.isHorizontal = true
                writeLabelIfPresent(model, shape, lane.id, layout)
                plane.addChildElement(shape)
            }
        }
    }

    /**
     * Writes BPMNEdge for [MessageFlow] elements.
     */
    private fun writeMessageEdges(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
        existingEdges: Map<String, BpmnEdge>,
    ) {
        for (mf in model.getModelElementsByType(MessageFlow::class.java).sortedBy { it.id }) {
            val waypoints = layout.edges[mf.id]
            if (waypoints.isNullOrEmpty()) continue
            existingEdges[mf.id]?.also { existing ->
                existing.waypoints.clear()
                model.fillWaypoints(existing, waypoints)
                layout.labels[mf.id]?.let { labelRect ->
                    val bpmnLabel = existing.bpmnLabel ?: model.newInstance(BpmnLabel::class.java).also {
                        it.id = "BPMNLabel_${mf.id}"
                        existing.bpmnLabel = it
                    }
                    bpmnLabel.bounds = model.newBounds(labelRect.x, labelRect.y, labelRect.w, labelRect.h)
                }
                plane.addChildElement(existing)
            } ?: model.newInstance(BpmnEdge::class.java).also { bpmnEdge ->
                bpmnEdge.id = "BPMNEdge_${mf.id}"
                bpmnEdge.bpmnElement = mf
                model.fillWaypoints(bpmnEdge, waypoints)
                layout.labels[mf.id]?.let { labelRect ->
                    val bpmnLabel = model.newInstance(BpmnLabel::class.java)
                    bpmnLabel.id = "BPMNLabel_${mf.id}"
                    bpmnLabel.bounds = model.newBounds(labelRect.x, labelRect.y, labelRect.w, labelRect.h)
                    bpmnEdge.bpmnLabel = bpmnLabel
                }
                plane.addChildElement(bpmnEdge)
            }
        }
    }

    /** Writes BPMNShape for all non-boundary FlowNodes using geometry from [PlacedLayout.shapes]. */
    private fun writeFlowNodeShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
        existingShapes: Map<String, BpmnShape>,
    ) {
        for (flowNode in model.getModelElementsByType(FlowNode::class.java)
            .filter { it !is BoundaryEvent }
            .sortedBy { it.id }) {
            val rect = layout.shapes[flowNode.id] ?: continue
            existingShapes[flowNode.id]?.also { existing ->
                existing.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                if (flowNode is SubProcess) existing.isExpanded = flowNode.id in layout.expanded
                writeLabelIfPresent(model, existing, flowNode.id, layout)
                plane.addChildElement(existing)
            } ?: model.newInstance(BpmnShape::class.java).also { shape ->
                shape.id = "BPMNShape_${flowNode.id}"
                shape.bpmnElement = flowNode
                shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                if (flowNode is SubProcess) shape.isExpanded = flowNode.id in layout.expanded
                writeLabelIfPresent(model, shape, flowNode.id, layout)
                plane.addChildElement(shape)
            }
        }
    }

    /** Writes BPMNShape for BoundaryEvents using geometry from [PlacedLayout.shapes]. */
    private fun writeBoundaryShapes(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
        existingShapes: Map<String, BpmnShape>,
    ) {
        for (be in model.getModelElementsByType(BoundaryEvent::class.java).sortedBy { it.id }) {
            val rect = layout.shapes[be.id] ?: continue
            existingShapes[be.id]?.also { existing ->
                existing.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                writeLabelIfPresent(model, existing, be.id, layout)
                plane.addChildElement(existing)
            } ?: model.newInstance(BpmnShape::class.java).also { shape ->
                shape.id = "BPMNShape_${be.id}"
                shape.bpmnElement = be
                shape.bounds = model.newBounds(rect.x, rect.y, rect.w, rect.h)
                writeLabelIfPresent(model, shape, be.id, layout)
                plane.addChildElement(shape)
            }
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
     * Reuses an existing [BpmnLabel] on the shape when present (DI-merge path) so
     * re-layout updates label geometry rather than accumulating stale coordinates.
     * Never copies the element's own coordinates — labels come exclusively from [PlacedLayout.labels].
     */
    private fun writeLabelIfPresent(
        model: BpmnModelInstance,
        shape: BpmnShape,
        ownerId: String,
        layout: PlacedLayout,
    ) {
        val labelRect = layout.labels[ownerId] ?: return
        val bpmnLabel = shape.bpmnLabel ?: model.newInstance(BpmnLabel::class.java).also {
            it.id = "BPMNLabel_$ownerId"
            shape.bpmnLabel = it
        }
        bpmnLabel.bounds = model.newBounds(labelRect.x, labelRect.y, labelRect.w, labelRect.h)
    }

    private fun writeSequenceEdges(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
        existingEdges: Map<String, BpmnEdge>,
    ) {
        for (sf in model.getModelElementsByType(SequenceFlow::class.java).sortedBy { it.id }) {
            val waypoints = layout.edges[sf.id]
            if (waypoints.isNullOrEmpty()) {
                throw BpmnAutoLayoutException(
                    "ELK layout: sequence flow '${sf.id}' has no waypoints in PlacedLayout — " +
                        "ELK did not produce routing for this edge",
                )
            }
            existingEdges[sf.id]?.also { existing ->
                existing.waypoints.clear()
                model.fillWaypoints(existing, waypoints)
                layout.labels[sf.id]?.let { labelRect ->
                    val bpmnLabel = existing.bpmnLabel ?: model.newInstance(BpmnLabel::class.java).also {
                        it.id = "BPMNLabel_${sf.id}"
                        existing.bpmnLabel = it
                    }
                    bpmnLabel.bounds = model.newBounds(labelRect.x, labelRect.y, labelRect.w, labelRect.h)
                }
                plane.addChildElement(existing)
            } ?: model.newInstance(BpmnEdge::class.java).also { bpmnEdge ->
                bpmnEdge.id = "BPMNEdge_${sf.id}"
                bpmnEdge.bpmnElement = sf
                model.fillWaypoints(bpmnEdge, waypoints)
                layout.labels[sf.id]?.let { labelRect ->
                    val bpmnLabel = model.newInstance(BpmnLabel::class.java)
                    bpmnLabel.id = "BPMNLabel_${sf.id}"
                    bpmnLabel.bounds = model.newBounds(labelRect.x, labelRect.y, labelRect.w, labelRect.h)
                    bpmnEdge.bpmnLabel = bpmnLabel
                }
                plane.addChildElement(bpmnEdge)
            }
        }
    }

    private fun writeAssociationEdges(
        model: BpmnModelInstance,
        plane: BpmnPlane,
        layout: PlacedLayout,
        existingEdges: Map<String, BpmnEdge>,
    ) {
        for (assoc in model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Association::class.java)
            .sortedBy { it.id }) {
            val waypoints = layout.edges[assoc.id] ?: continue
            existingEdges[assoc.id]?.also { existing ->
                existing.waypoints.clear()
                model.fillWaypoints(existing, waypoints)
                plane.addChildElement(existing)
            } ?: model.newInstance(BpmnEdge::class.java).also { bpmnEdge ->
                bpmnEdge.id = "BPMNEdge_${assoc.id}"
                bpmnEdge.bpmnElement = assoc
                model.fillWaypoints(bpmnEdge, waypoints)
                plane.addChildElement(bpmnEdge)
            }
        }
    }

    /**
     * Writes [waypoints] to [edge], skipping consecutive duplicates.
     */
    private fun BpmnModelInstance.fillWaypoints(
        edge: BpmnEdge,
        waypoints: List<BpmnPlacementPass.Point>,
    ) {
        for ((index, waypoint) in waypoints.withIndex()) {
            if (index > 0 && waypoint == waypoints[index - 1]) continue
            newWaypoint(waypoint.x, waypoint.y).also { edge.waypoints.add(it) }
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
