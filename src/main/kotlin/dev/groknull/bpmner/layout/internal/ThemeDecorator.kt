/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.ruleset.ThemeConfig
import org.camunda.bpm.model.bpmn.BpmnModelInstance
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnEdge
import org.camunda.bpm.model.bpmn.instance.bpmndi.BpmnShape
import org.camunda.bpm.model.xml.instance.DomElement
import org.camunda.bpm.model.xml.instance.ModelElementInstance

/**
 * Post-processes the BPMN DI [ElkToBpmnDiWriter] just wrote, attaching standard BPMN 2.0 DI
 * colour attributes so viewers render the active [ThemeConfig].
 *
 * Writes both the `bioc:` (bpmn.io) and `color:` (OMG non-normative) namespace families per
 * ARCHITECTURE.md's cross-viewer compatibility mitigation. Resolves each shape/edge's style from
 * [ThemeConfig.shapeOverrides], keyed by the owning BPMN element's type (e.g. `bpmn:Task`),
 * falling back to the theme's global [ThemeConfig.secondaryColor] (stroke) and
 * [ThemeConfig.backgroundColor] (fill). Never overwrites a colour attribute already present on
 * the element — [ElkToBpmnDiWriter] re-attaches author-preserved shapes/edges before this runs,
 * and overwriting would destroy that preservation ([DIMergeTest]).
 */
internal object ThemeDecorator {
    private const val BIOC_NS = "http://bpmn.io/schema/bpmn/biocolor/1.0"
    private const val COLOR_NS = "http://www.omg.org/spec/BPMN/non-normative/color/1.0"

    fun decorate(model: BpmnModelInstance, theme: ThemeConfig) {
        model.document.registerNamespace("bioc", BIOC_NS)
        model.document.registerNamespace("color", COLOR_NS)

        for (shape in model.getModelElementsByType(BpmnShape::class.java)) {
            val style = shape.bpmnElement?.let { theme.shapeOverrides[it.typeKey()] }
            val fill = style?.fill ?: theme.backgroundColor
            val stroke = style?.stroke ?: theme.secondaryColor
            shape.domElement.writeColorIfAbsent(fill = fill, stroke = stroke)
        }

        for (edge in model.getModelElementsByType(BpmnEdge::class.java)) {
            val style = edge.bpmnElement?.let { theme.shapeOverrides[it.typeKey()] }
            val stroke = style?.stroke ?: theme.secondaryColor
            edge.domElement.writeColorIfAbsent(fill = null, stroke = stroke)
        }
    }

    private fun ModelElementInstance.typeKey(): String =
        "bpmn:${elementType.typeName.replaceFirstChar { it.uppercase() }}"

    private fun DomElement.writeColorIfAbsent(fill: String?, stroke: String?) {
        if (fill != null) writeChannelIfAbsent(fallback = fill, biocName = "fill", colorName = "background-color")
        if (stroke != null) writeChannelIfAbsent(fallback = stroke, biocName = "stroke", colorName = "border-color")
    }

    /**
     * Resolves the existing author colour from either namespace (an imported diagram may carry
     * only `bioc:` or only `color:`), then writes only whichever namespace attribute is missing —
     * preserving an author colour found in either namespace rather than overwriting it, while
     * still filling in the counterpart namespace for cross-viewer compatibility.
     */
    private fun DomElement.writeChannelIfAbsent(fallback: String, biocName: String, colorName: String) {
        val existing = getAttribute(BIOC_NS, biocName) ?: getAttribute(COLOR_NS, colorName)
        val value = existing ?: fallback
        if (!hasAttribute(BIOC_NS, biocName)) setAttribute(BIOC_NS, biocName, value)
        if (!hasAttribute(COLOR_NS, colorName)) setAttribute(COLOR_NS, colorName, value)
    }
}
