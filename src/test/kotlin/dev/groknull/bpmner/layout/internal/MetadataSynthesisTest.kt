/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.camunda.bpm.model.bpmn.instance.TextAnnotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class MetadataSynthesisTest {
    private val layouter = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }

    @Test
    fun `layout synthesizes metadata annotations and preserves them on a subsequent layout`() {
        val input = javaClass.classLoader.getResourceAsStream("layout-fixtures/representative-process.bpmn")!!
            .use { it.readBytes().toString(Charsets.UTF_8) }

        val first = layouter.layout(input)
        val second = layouter.layout(first)

        assertEquals(MetadataSynthesis.markerIds, annotationIds(first))
        assertEquals(MetadataSynthesis.markerIds, annotationIds(second))
        assertTrue(first.contains("bpmner-diagram-header"))
    }

    private fun annotationIds(xml: String): Set<String> = Bpmn.readModelFromStream(
        ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)),
    ).getModelElementsByType(TextAnnotation::class.java)
        .mapTo(mutableSetOf()) { it.id }
        .intersect(MetadataSynthesis.markerIds)
}
