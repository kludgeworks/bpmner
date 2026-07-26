/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Group E probe (622-Y, AD-622-09): pins ELK's own 0/N-connection comment-attachment fallback
 * on real data, rather than assuming it from the source.
 *
 * `CommentPreprocessor` lifts a comment box out for `CommentPostprocessor`'s above/below
 * placement only when it has *exactly one* connection to a port of degree 1. `annotation-and-
 * group.bpmn` (the enrolled golden) exercises that single-connection path. `annotation-multi-
 * host.bpmn` has one `TextAnnotation` with **two** associations — `Task_1` and `Task_2` each
 * point at it — which disqualifies the lift-out, so ELK's own documented fallback applies:
 * "processed normally, i.e. they are treated as regular nodes". `BpmnToElkMapper.trackAnnotations`
 * maps every association regardless of count (622-Y group E), so this is what actually runs
 * through the engine rather than a guess at what *would* happen.
 *
 * Un-enrolled: no golden, no [LAYOUT_CORPUS_FIXTURES] entry, no baseline row (AD-622-24's
 * generalised rule) — this probe only asserts the fixture lays out to structurally valid DI and
 * that both associations produced a real edge, recording the annotation's resulting geometry so
 * a future session enrolling this fixture (or investigating a `Group_1`-scale defect) starts
 * from measured fact.
 */
class AnnotationMultiHostProbeTest {

    @Test
    fun `an annotation with two associations lays out as a regular node with both edges routed`() {
        val xml = load("layout-fixtures/annotation-multi-host.bpmn")
        val result = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(result)

        val shapes = extractShapeRects(doc).associateBy { it.id }
        val annotation = shapes["Anno_1"]
        assertTrue(annotation != null, "Anno_1 must have a positive-area BPMNShape")

        val edges = extractEdges(doc).associateBy { it.id }
        val assoc1 = edges["Assoc_1"]
        val assoc2 = edges["Assoc_2"]
        assertTrue(assoc1 != null && assoc1.waypoints.size >= 2, "Assoc_1 must have a real, >=2-waypoint route")
        assertTrue(assoc2 != null && assoc2.waypoints.size >= 2, "Assoc_2 must have a real, >=2-waypoint route")

        println(
            "[annotation-multi-host] Anno_1 laid out at $annotation; " +
                "Assoc_1=${assoc1!!.waypoints}, Assoc_2=${assoc2!!.waypoints} " +
                "(un-enrolled probe — no baseline; pins ELK's regular-node fallback for a " +
                "multi-connection comment box, per PLAN-622-Y.md group E)",
        )
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()?.readText() ?: error("resource not found: $resource")
}
