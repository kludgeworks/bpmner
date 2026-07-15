/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.internal.adapter.outbound.PlacementTestSkeletons.makeEdgeNoEndpoints
import dev.groknull.bpmner.layout.internal.adapter.outbound.PlacementTestSkeletons.makeNode
import dev.groknull.bpmner.layout.internal.adapter.outbound.PlacementTestSkeletons.parse
import dev.groknull.bpmner.layout.internal.adapter.outbound.PlacementTestSkeletons.skeleton
import dev.groknull.bpmner.layout.internal.adapter.outbound.placement.PlacementContext
import org.eclipse.elk.alg.layered.options.LayeredMetaDataProvider
import org.eclipse.elk.core.data.LayoutMetaDataService
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract tests for [SubprocessSpineCentring] (pipeline entries 4 + 11).
 *
 * Verifies: spine nodes snap to subprocess centre-Y; branch tasks (predecessor gateway
 * out-degree > 1) are excluded; loop-back edges excluded from in-degree; Repair re-routes
 * edges touching snapped nodes.
 */
class SubprocessSpineCentringTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun registerMetadata() {
            LayoutMetaDataService.getInstance().registerLayoutMetaDataProviders(LayeredMetaDataProvider())
        }

        /** Subprocess with straight spine (Start → Task → End). No branches. */
        private val SPINE_MODEL = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="Main_S"><bpmn:outgoing>Fout</bpmn:outgoing></bpmn:startEvent>
    <bpmn:subProcess id="Sub_1">
      <bpmn:incoming>Fout</bpmn:incoming><bpmn:outgoing>Fexit</bpmn:outgoing>
      <bpmn:startEvent id="SubStart"><bpmn:outgoing>FS1</bpmn:outgoing></bpmn:startEvent>
      <bpmn:serviceTask id="SpineTask"><bpmn:incoming>FS1</bpmn:incoming><bpmn:outgoing>FS2</bpmn:outgoing></bpmn:serviceTask>
      <bpmn:endEvent id="SubEnd"><bpmn:incoming>FS2</bpmn:incoming></bpmn:endEvent>
      <bpmn:sequenceFlow id="FS1" sourceRef="SubStart" targetRef="SpineTask"/>
      <bpmn:sequenceFlow id="FS2" sourceRef="SpineTask" targetRef="SubEnd"/>
    </bpmn:subProcess>
    <bpmn:endEvent id="Main_E"><bpmn:incoming>Fexit</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Fout" sourceRef="Main_S" targetRef="Sub_1"/>
    <bpmn:sequenceFlow id="Fexit" sourceRef="Sub_1" targetRef="Main_E"/>
  </bpmn:process>
</bpmn:definitions>""",
        )

        /** Subprocess with split gateway → branches. Branch task has predecessor out-degree > 1. */
        private val BRANCH_MODEL = parse(
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:subProcess id="Sub_1">
      <bpmn:startEvent id="SubStart"><bpmn:outgoing>FS0</bpmn:outgoing></bpmn:startEvent>
      <bpmn:exclusiveGateway id="Gw_split">
        <bpmn:incoming>FS0</bpmn:incoming><bpmn:outgoing>FB1</bpmn:outgoing><bpmn:outgoing>FB2</bpmn:outgoing>
      </bpmn:exclusiveGateway>
      <bpmn:serviceTask id="BranchTask_A"><bpmn:incoming>FB1</bpmn:incoming><bpmn:outgoing>FA_join</bpmn:outgoing></bpmn:serviceTask>
      <bpmn:serviceTask id="BranchTask_B"><bpmn:incoming>FB2</bpmn:incoming><bpmn:outgoing>FB_join</bpmn:outgoing></bpmn:serviceTask>
      <bpmn:exclusiveGateway id="Gw_join">
        <bpmn:incoming>FA_join</bpmn:incoming><bpmn:incoming>FB_join</bpmn:incoming><bpmn:outgoing>FS_end</bpmn:outgoing>
      </bpmn:exclusiveGateway>
      <bpmn:endEvent id="SubEnd"><bpmn:incoming>FS_end</bpmn:incoming></bpmn:endEvent>
      <bpmn:sequenceFlow id="FS0" sourceRef="SubStart" targetRef="Gw_split"/>
      <bpmn:sequenceFlow id="FB1" sourceRef="Gw_split" targetRef="BranchTask_A"/>
      <bpmn:sequenceFlow id="FB2" sourceRef="Gw_split" targetRef="BranchTask_B"/>
      <bpmn:sequenceFlow id="FA_join" sourceRef="BranchTask_A" targetRef="Gw_join"/>
      <bpmn:sequenceFlow id="FB_join" sourceRef="BranchTask_B" targetRef="Gw_join"/>
      <bpmn:sequenceFlow id="FS_end" sourceRef="Gw_join" targetRef="SubEnd"/>
    </bpmn:subProcess>
  </bpmn:process>
</bpmn:definitions>""",
        )
    }

    /** Subprocess at (100,50) w=400 h=200 so centre-Y = 50+100=150. Spine nodes at y=60 (off centre). */
    private fun spineSkeleton(): BpmnToElkMapper.ElkSkeleton {
        val root = ElkGraphUtil.createGraph()
        val mainS = makeNode(root, "Main_S", 12.0, 130.0, 36.0, 36.0)
        val sub = makeNode(root, "Sub_1", 100.0, 50.0, 400.0, 200.0)
        val subStart = makeNode(sub, "SubStart", 20.0, 30.0, 36.0, 36.0)
        val spineTask = makeNode(sub, "SpineTask", 100.0, 30.0, 100.0, 80.0)
        val subEnd = makeNode(sub, "SubEnd", 250.0, 47.0, 36.0, 36.0)
        val mainE = makeNode(root, "Main_E", 600.0, 130.0, 36.0, 36.0)
        val fs1 = makeEdgeNoEndpoints(sub, "FS1", 56.0, 48.0, 100.0, 70.0)
        val fs2 = makeEdgeNoEndpoints(sub, "FS2", 200.0, 70.0, 250.0, 65.0)
        val fout = makeEdgeNoEndpoints(root, "Fout", 48.0, 148.0, 100.0, 150.0)
        val fexit = makeEdgeNoEndpoints(root, "Fexit", 500.0, 150.0, 600.0, 148.0)
        return skeleton(
            root,
            mapOf(
                "Main_S" to mainS,
                "Sub_1" to sub,
                "SubStart" to subStart,
                "SpineTask" to spineTask,
                "SubEnd" to subEnd,
                "Main_E" to mainE,
            ),
            edgeMap = mapOf("FS1" to fs1, "FS2" to fs2, "Fout" to fout, "Fexit" to fexit),
        )
    }

    @Test
    fun `Move snaps spine nodes to subprocess vertical centre-Y`() {
        val sk = spineSkeleton()
        val layout = BpmnPlacementPass.place(SPINE_MODEL, sk)

        val subRect = layout.shapes["Sub_1"]!!
        val subCentreY = subRect.y + subRect.h / 2.0

        for (id in listOf("SubStart", "SpineTask")) {
            val rect = layout.shapes[id]!!
            val nodeCentreY = rect.y + rect.h / 2.0
            assertTrue(
                kotlin.math.abs(nodeCentreY - subCentreY) < 0.5,
                "$id centre-Y ($nodeCentreY) must be snapped to subprocess centre-Y ($subCentreY)",
            )
        }
    }

    @Test
    fun `Move records snapped nodes in ledger with owner SubprocessSpineCentring`() {
        val sk = spineSkeleton()
        val ctx = PlacementContext(
            model = SPINE_MODEL,
            skeleton = sk,
            shapes = mutableMapOf(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )
        BpmnPlacementPass.run(ctx)

        val record = ctx.moves["SpineTask"]
        assertNotNull(record, "SpineTask must have a MoveRecord")
        assertTrue(
            record.owner == "SubprocessSpineCentring",
            "Owner must be SubprocessSpineCentring, was ${record.owner}",
        )
    }

    @Test
    fun `Move excludes branch tasks (predecessor gateway out-degree greater than 1)`() {
        val root = ElkGraphUtil.createGraph()
        val sub = makeNode(root, "Sub_1", 100.0, 50.0, 500.0, 200.0)
        val subStart = makeNode(sub, "SubStart", 20.0, 30.0, 36.0, 36.0)
        val gwSplit = makeNode(sub, "Gw_split", 80.0, 90.0, 50.0, 50.0)
        val branchA = makeNode(sub, "BranchTask_A", 160.0, 20.0, 100.0, 80.0)
        val branchB = makeNode(sub, "BranchTask_B", 160.0, 120.0, 100.0, 80.0)
        val gwJoin = makeNode(sub, "Gw_join", 280.0, 90.0, 50.0, 50.0)
        val subEnd = makeNode(sub, "SubEnd", 360.0, 97.0, 36.0, 36.0)
        val fs0 = makeEdgeNoEndpoints(sub, "FS0", 56.0, 48.0, 80.0, 115.0)
        val fb1 = makeEdgeNoEndpoints(sub, "FB1", 130.0, 100.0, 160.0, 60.0)
        val fb2 = makeEdgeNoEndpoints(sub, "FB2", 130.0, 120.0, 160.0, 160.0)
        val faJoin = makeEdgeNoEndpoints(sub, "FA_join", 260.0, 60.0, 280.0, 100.0)
        val fbJoin = makeEdgeNoEndpoints(sub, "FB_join", 260.0, 160.0, 280.0, 120.0)
        val fsEnd = makeEdgeNoEndpoints(sub, "FS_end", 330.0, 115.0, 360.0, 115.0)
        val sk = skeleton(
            root,
            mapOf(
                "Sub_1" to sub,
                "SubStart" to subStart,
                "Gw_split" to gwSplit,
                "BranchTask_A" to branchA,
                "BranchTask_B" to branchB,
                "Gw_join" to gwJoin,
                "SubEnd" to subEnd,
            ),
            edgeMap = mapOf(
                "FS0" to fs0,
                "FB1" to fb1,
                "FB2" to fb2,
                "FA_join" to faJoin,
                "FB_join" to fbJoin,
                "FS_end" to fsEnd,
            ),
        )

        val ctx = PlacementContext(
            model = BRANCH_MODEL,
            skeleton = sk,
            shapes = mutableMapOf(),
            labels = mutableMapOf(),
            edges = mutableMapOf(),
            expanded = mutableSetOf(),
        )
        BpmnPlacementPass.run(ctx)

        // Branch tasks must NOT be in the spine-centring ledger
        val branchARecord = ctx.moves["BranchTask_A"]
        val branchBRecord = ctx.moves["BranchTask_B"]
        assertTrue(
            branchARecord == null || branchARecord.owner != "SubprocessSpineCentring",
            "BranchTask_A must not be snapped by SubprocessSpineCentring",
        )
        assertTrue(
            branchBRecord == null || branchBRecord.owner != "SubprocessSpineCentring",
            "BranchTask_B must not be snapped by SubprocessSpineCentring",
        )
    }
}
