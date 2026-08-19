/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.placement.LabelMetrics
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.Text
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.graph.ElkNode

internal object MetadataSynthesis {
    // These marker IDs must stay in sync with MetadataPresenceRules.
    const val HEADER_ID = "bpmner-diagram-header"
    const val NOTES_ID = "bpmner-diagram-notes"
    const val LEGEND_ID = "bpmner-diagram-legend"

    private const val HORIZONTAL_PADDING = 20.0
    private const val VERTICAL_PADDING = 10.0

    fun addMissingAnnotations(model: BpmnModelInstance) {
        val process = model.getModelElementsByType(Process::class.java).first()
        val existingIds = model.getModelElementsByType(TextAnnotation::class.java).mapTo(mutableSetOf()) { it.id }
        val participant = model.getModelElementsByType(Participant::class.java).singleOrNull()
        val header = participant?.let { "${it.name ?: it.id} (${it.id})" } ?: "${process.name ?: process.id} (${process.id})"
        addAnnotation(model, process, existingIds, HEADER_ID, header)
        addAnnotation(model, process, existingIds, NOTES_ID, "Diagram notes: review this process with its stakeholders.")
        addAnnotation(model, process, existingIds, LEGEND_ID, "Legend: BPMN symbols describe the process flow.")
    }

    fun reserveTopPadding(model: BpmnModelInstance, root: ElkNode) {
        val annotations = model.getModelElementsByType(TextAnnotation::class.java).filter { it.id in markerIds }
        val height = annotations.sumOf { LabelMetrics.LINE_HEIGHT + 2 * VERTICAL_PADDING }
        root.setProperty(CoreOptions.PADDING, ElkPadding(height, 0.0, 0.0, 0.0))
    }

    private fun addAnnotation(
        model: BpmnModelInstance,
        process: Process,
        existingIds: MutableSet<String>,
        id: String,
        text: String,
    ) {
        if (!existingIds.add(id)) return
        process.addChildElement(
            model.newInstance(TextAnnotation::class.java).also {
                it.id = id
                it.text = model.newInstance(Text::class.java).also { content -> content.textContent = text }
            },
        )
    }

    internal val markerIds = setOf(HEADER_ID, NOTES_ID, LEGEND_ID)
    internal fun annotationHeight(): Double =
        LabelMetrics.LINE_HEIGHT + 2 * VERTICAL_PADDING
    internal fun annotationWidth(annotation: TextAnnotation): Double =
        LabelMetrics.width(annotation.text?.textContent.orEmpty()) + 2 * HORIZONTAL_PADDING
}
