/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import kotlin.test.assertTrue

/**
 * Corpus-wide tail/perpendicularity invariant (AD-622-10), replacing the deleted
 * `EdgeTerminalTailGuard` mutator: rather than post-processing a too-short terminal segment,
 * this asserts every sequence-flow edge's engine-routed geometry already satisfies the contract
 * the guard used to enforce by hand — first and last segments axis-parallel, each terminal
 * waypoint anchored on its endpoint shape's boundary, and the *final* segment (the one leading
 * into the target's arrowhead — the deleted guard's own scope) at least [MIN_TERMINAL_TAIL] long.
 * Covers reversed loop-back edges (AD-622-15) too, whose terminals are the ones most likely to
 * surprise.
 */
class EdgeTerminalTailGuardTest {

    private val layouter = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }

    companion object {
        @JvmStatic
        fun fixtures() = LAYOUT_CORPUS_FIXTURES
    }

    @ParameterizedTest(name = "edge terminal tail/perpendicularity: {0}")
    @MethodSource("fixtures")
    fun `every sequence-flow edge has axis-parallel, boundary-anchored terminals with adequate tail length`(
        fixture: String,
    ) {
        val input = load("layout-fixtures/$fixture.bpmn")
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)))
        val flowsById = model.getModelElementsByType(org.camunda.bpm.model.bpmn.instance.SequenceFlow::class.java)
            .associateBy { it.id }

        val doc = LayoutDiInspector.parse(layouter.layout(input))
        val shapesById = extractShapeRects(doc).associateBy { it.id }

        extractEdges(doc)
            .filter { it.id in flowsById && it.waypoints.size >= 2 }
            .forEach { edge -> assertTerminals(fixture, edge, flowsById.getValue(edge.id), shapesById) }
    }

    private fun assertTerminals(
        fixture: String,
        edge: DiEdge,
        sf: org.camunda.bpm.model.bpmn.instance.SequenceFlow,
        shapesById: Map<String, DiRect>,
    ) {
        val wps = edge.waypoints
        assertTrue(
            isAxisAligned(wps[0], wps[1]),
            "[$fixture] edge '${edge.id}' first segment ${wps[0]}→${wps[1]} must be axis-parallel",
        )
        assertTrue(
            isAxisAligned(wps[wps.size - 2], wps.last()),
            "[$fixture] edge '${edge.id}' last segment ${wps[wps.size - 2]}→${wps.last()} must be axis-parallel",
        )
        assertTrue(
            axisSegmentLength(wps[wps.size - 2], wps.last()) >= MIN_TERMINAL_TAIL,
            "[$fixture] edge '${edge.id}' final segment (into the arrowhead) must be >= $MIN_TERMINAL_TAIL long",
        )
        shapesById[sf.source?.id]?.let { sourceRect ->
            assertTrue(
                isOnRectBoundary(wps[0], sourceRect),
                "[$fixture] edge '${edge.id}' start ${wps[0]} must sit on source '${sf.source?.id}' boundary $sourceRect",
            )
        }
        shapesById[sf.target?.id]?.let { targetRect ->
            assertTrue(
                isOnRectBoundary(wps.last(), targetRect),
                "[$fixture] edge '${edge.id}' end ${wps.last()} must sit on target '${sf.target?.id}' boundary $targetRect",
            )
        }
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.use { it.readBytes().toString(Charsets.UTF_8) }
        ?: error("Resource not found: $resource")
}
