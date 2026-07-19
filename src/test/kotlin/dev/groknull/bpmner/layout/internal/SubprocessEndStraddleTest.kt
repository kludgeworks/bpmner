/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.makeEdgeNoEndpoints
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.makeNode
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.parse
import dev.groknull.bpmner.layout.internal.PlacementTestSkeletons.skeleton
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for [SubprocessEndStraddle] (pipeline entries 3 + 10).
 *
 * Verifies: terminating end events are centred on the subprocess right border;
 * exit flows exit from the straddled end's right edge; move is ledgered.
 */
class SubprocessEndStraddleTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        private val SUBPROCESS_MODEL = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>F0</bpmn:outgoing></bpmn:startEvent>
    <bpmn:subProcess id="Sub_1">
      <bpmn:incoming>F0</bpmn:incoming><bpmn:outgoing>Fexit</bpmn:outgoing>
      <bpmn:startEvent id="SubStart"><bpmn:outgoing>FS1</bpmn:outgoing></bpmn:startEvent>
      <bpmn:endEvent id="SubEnd"><bpmn:incoming>FS1</bpmn:incoming></bpmn:endEvent>
      <bpmn:sequenceFlow id="FS1" sourceRef="SubStart" targetRef="SubEnd"/>
    </bpmn:subProcess>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Fexit</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="F0" sourceRef="Start_1" targetRef="Sub_1"/>
    <bpmn:sequenceFlow id="Fexit" sourceRef="Sub_1" targetRef="End_1"/>
  </bpmn:process>
</bpmn:definitions>""",
        )
    }

    /**
     * Subprocess at (100,50) with w=300, h=200.
     * SubEnd at (350,110) — ELK-placed, will be straddled by Move.
     */
    private fun subprocessSkeleton(): BpmnToElkMapper.ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        val start = makeNode(root, "Start_1", 12.0, 175.0, 36.0, 36.0)
        val sub = makeNode(root, "Sub_1", 100.0, 50.0, 300.0, 200.0)
        val subStart = makeNode(sub, "SubStart", 20.0, 82.0, 36.0, 36.0)
        val subEnd = makeNode(sub, "SubEnd", 230.0, 82.0, 36.0, 36.0)
        val end = makeNode(root, "End_1", 500.0, 175.0, 36.0, 36.0)
        val fs1 = makeEdgeNoEndpoints(sub, "FS1", 56.0, 100.0, 230.0, 100.0)
        val f0 = makeEdgeNoEndpoints(root, "F0", 48.0, 193.0, 100.0, 150.0)
        val fexit = makeEdgeNoEndpoints(root, "Fexit", 400.0, 150.0, 500.0, 193.0)
        return skeleton(
            root,
            mapOf(
                "Start_1" to start,
                "Sub_1" to sub,
                "SubStart" to subStart,
                "SubEnd" to subEnd,
                "End_1" to end,
            ),
            edgeMap = mapOf("FS1" to fs1, "F0" to f0, "Fexit" to fexit),
        )
    }

    @Test
    fun `Move centres subprocess end event on right border of subprocess (straddle)`() {
        val sk = subprocessSkeleton()
        val layout = BpmnPlacementPass.place(SUBPROCESS_MODEL, sk)

        val subRect = layout.shapes["Sub_1"]!!
        val subEndRect = layout.shapes["SubEnd"]
        assertNotNull(subEndRect, "SubEnd must be in shapes")

        val rightBorder = subRect.x + subRect.w
        val endCentreX = subEndRect.x + subEndRect.w / 2.0

        assertTrue(
            kotlin.math.abs(endCentreX - rightBorder) < 0.5,
            "SubEnd centre-X ($endCentreX) must be at subprocess right border ($rightBorder)",
        )
    }

    @Test
    fun `Move records straddled end in the move ledger with owner SubprocessEndStraddle`() {
        val sk = subprocessSkeleton()
        val ctx = dev.groknull.bpmner.layout.internal.placement.PlacementContext(
            model = SUBPROCESS_MODEL,
            skeleton = sk,
            shapes = mutableMapOf(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )
        BpmnPlacementPass.run(ctx)

        val record = ctx.moves["SubEnd"]
        assertNotNull(record, "SubEnd must have a MoveRecord in the ledger")
        assertTrue(
            record.owner == "SubprocessEndStraddle",
            "Owner must be SubprocessEndStraddle, was ${record.owner}",
        )
    }

    @Test
    fun `Repair re-anchors exit flow to start from straddled end right edge`() {
        val sk = subprocessSkeleton()
        val layout = BpmnPlacementPass.place(SUBPROCESS_MODEL, sk)

        val subEndRect = layout.shapes["SubEnd"]!!
        val fexit = layout.edges["Fexit"]
        assertNotNull(fexit, "Fexit must have waypoints")
        assertTrue(fexit.size >= 2, "Fexit must have ≥ 2 waypoints")

        // First waypoint must be the straddled end's RIGHT edge
        val expectedStartX = subEndRect.x + subEndRect.w
        assertTrue(
            kotlin.math.abs(fexit.first().x - expectedStartX) < 0.5,
            "Fexit wp0.x (${fexit.first().x}) must be SubEnd right edge ($expectedStartX)",
        )
    }
}
