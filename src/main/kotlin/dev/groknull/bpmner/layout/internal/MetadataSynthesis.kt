/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.bpmn.DiagramMetadata
import dev.groknull.bpmner.bpmn.DiagramStatusColors
import dev.groknull.bpmner.layout.internal.placement.LabelMetrics
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.Participant
import org.camunda.bpm.model.bpmn.instance.Process
import org.camunda.bpm.model.bpmn.instance.Text
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.eclipse.elk.core.math.ElkPadding
import org.eclipse.elk.core.options.CoreOptions
import org.eclipse.elk.graph.ElkNode
import java.time.Instant

internal object MetadataSynthesis {
    const val HEADER_ID = DiagramMetadata.HEADER_ID
    const val NOTES_ID = DiagramMetadata.NOTES_ID
    const val LEGEND_ID = DiagramMetadata.LEGEND_ID

    private const val HORIZONTAL_PADDING = 20.0
    private const val VERTICAL_PADDING = 10.0

    fun addMissingAnnotations(model: BpmnModelInstance, instant: Instant, colors: DiagramStatusColors) {
        val process = model.getModelElementsByType(Process::class.java).first()
        val participant = model.getModelElementsByType(Participant::class.java).singleOrNull()
        val processName = participant?.name ?: process.name ?: process.id
        val annotations = model.getModelElementsByType(TextAnnotation::class.java).associateBy { it.id }
        val content = listOf(
            AnnotationContent(HEADER_ID, DiagramMetadata.header(processName, process.id)) {
                DiagramMetadata.hasValidHeader(it, processName, process.id)
            },
            AnnotationContent(
                NOTES_ID,
                DiagramMetadata.completeNotes(annotations[NOTES_ID]?.text?.textContent, processName, instant),
            ) {
                DiagramMetadata.hasValidNotes(it)
            },
            AnnotationContent(LEGEND_ID, DiagramMetadata.completeLegend(annotations[LEGEND_ID]?.text?.textContent, colors)) {
                DiagramMetadata.hasValidLegend(it)
            },
        )
        content.forEach { addOrReplaceAnnotation(model, process, annotations[it.id], it) }
    }

    fun reserveTopPadding(model: BpmnModelInstance, root: ElkNode) {
        val annotations = model.getModelElementsByType(TextAnnotation::class.java).filter { it.id in markerIds }
        val height = annotations.sumOf { LabelMetrics.LINE_HEIGHT + 2 * VERTICAL_PADDING }
        root.setProperty(CoreOptions.PADDING, ElkPadding(height, 0.0, 0.0, 0.0))
    }

    private fun addOrReplaceAnnotation(
        model: BpmnModelInstance,
        process: Process,
        existing: TextAnnotation?,
        content: AnnotationContent,
    ) {
        if (existing?.text?.textContent?.let(content.valid) == true) return
        val annotation = existing ?: model.newInstance(TextAnnotation::class.java).also {
            it.id = content.id
            process.addChildElement(it)
        }
        annotation.text = model.newInstance(Text::class.java).also { it.textContent = content.text }
    }

    private data class AnnotationContent(val id: String, val text: String, val valid: (String) -> Boolean)

    internal val markerIds = DiagramMetadata.markerIds
    internal fun annotationHeight(): Double =
        LabelMetrics.LINE_HEIGHT + 2 * VERTICAL_PADDING
    internal fun annotationWidth(annotation: TextAnnotation): Double =
        LabelMetrics.width(annotation.text?.textContent.orEmpty()) + 2 * HORIZONTAL_PADDING
}
