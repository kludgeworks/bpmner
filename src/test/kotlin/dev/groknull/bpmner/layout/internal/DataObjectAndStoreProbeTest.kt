/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * `DataObjectReference`/`DataStoreReference` lay out via the same ELK comment-attachment
 * mechanism as `TextAnnotation` (`BpmnToElkMapper.trackDataReferences`).
 *
 * `data-object-and-store.bpmn`: `Task_place` writes `DataObjectRef_Order` (one connection —
 * lifted out, real comment box); `Task_archive` reads `DataObjectRef_Order` and writes
 * `DataStoreRef_Orders`. `DataObjectRef_Order` has *two* associations (from `Task_place` and
 * to `Task_archive`) — the same 0/N-connection fallback `AnnotationMultiHostProbeTest` pins
 * for annotations, exercised here for a data reference.
 *
 * This fixture is not enrolled in the layout corpus (no golden, no baseline row); this probe
 * records the resulting geometry for reference.
 */
class DataObjectAndStoreProbeTest {

    @Test
    fun `data object and data store references lay out with real associations routed`() {
        val xml = load("layout-fixtures/data-object-and-store.bpmn")
        val result = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(result)

        val shapes = extractShapeRects(doc).associateBy { it.id }
        val dataObjectRef = shapes["DataObjectRef_Order"]
        val dataStoreRef = shapes["DataStoreRef_Orders"]
        assertTrue(dataObjectRef != null, "DataObjectRef_Order must have a positive-area BPMNShape")
        assertTrue(dataStoreRef != null, "DataStoreRef_Orders must have a positive-area BPMNShape")

        val edges = extractEdges(doc).associateBy { it.id }
        val placeAssoc = edges["DataOutputAssociation_place"]
        val archiveInAssoc = edges["DataInputAssociation_archive"]
        val archiveOutAssoc = edges["DataOutputAssociation_archive"]
        assertTrue(
            placeAssoc != null && placeAssoc.waypoints.size >= 2,
            "DataOutputAssociation_place must have a real route",
        )
        assertTrue(
            archiveInAssoc != null && archiveInAssoc.waypoints.size >= 2,
            "DataInputAssociation_archive must have a real route",
        )
        assertTrue(
            archiveOutAssoc != null && archiveOutAssoc.waypoints.size >= 2,
            "DataOutputAssociation_archive must have a real route",
        )

        // Zero overlap between the two data-reference shapes and the flow-node shapes: same
        // convention ElkGoldenLayoutTest.assertNoTopLevelShapeOverlap checks corpus-wide.
        val flowShapes = shapes.filterKeys { it.startsWith("Task_") || it.startsWith("Start_") || it.startsWith("End_") }
        for (dataShape in listOf(dataObjectRef!!, dataStoreRef!!)) {
            for (flowShape in flowShapes.values) {
                val overlapX = minOf(dataShape.right, flowShape.right) - maxOf(dataShape.x, flowShape.x)
                val overlapY = minOf(dataShape.bottom, flowShape.bottom) - maxOf(dataShape.y, flowShape.y)
                assertTrue(
                    overlapX < 1.0 || overlapY < 1.0,
                    "${dataShape.id} ($dataShape) must not overlap ${flowShape.id} ($flowShape)",
                )
            }
        }

        println(
            "[data-object-and-store] DataObjectRef_Order=$dataObjectRef, DataStoreRef_Orders=$dataStoreRef " +
                "(un-enrolled probe — no baseline; pins the data-reference layout mechanism)",
        )
    }

    private fun load(resource: String): String = javaClass.classLoader.getResourceAsStream(resource)
        ?.bufferedReader()?.readText() ?: error("resource not found: $resource")
}
