/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.camunda.bpm.model.bpmn.Bpmn
import org.eclipse.elk.core.RecursiveGraphLayoutEngine
import org.eclipse.elk.core.util.BasicProgressMonitor
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NormalizedLaneRoutingProbeTest {

    @Test
    fun `stock ELK routes before lane bands are normalized`() {
        val model = Bpmn.readModelFromStream(ByteArrayInputStream(BRANCHED_LANES_XML.toByteArray(Charsets.UTF_8)))
        ElkBpmnLayouter().registerElkLayoutAlgorithm()
        val skeleton = BpmnToElkMapper.map(model)
        RecursiveGraphLayoutEngine().layout(skeleton.root, BasicProgressMonitor())

        val (_, topLaneY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_top"))
        val (_, lowerLaneY) = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Lane_lower"))
        assertTrue(
            topLaneY > lowerLaneY,
            "stock ELK places the lower declared lane above the top lane before normalization",
        )

        val rawTopTaskY = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Task_review")).second
        val rawLowerTaskY = BpmnPlacementPass.absolutePosition(skeleton.nodeMap.getValue("Task_verify")).second
        val rawCrossLaneRoute = rawRoute(skeleton.edgeMap.getValue("Flow_to_lower"))

        val placed = BpmnPlacementPass.place(model, skeleton)
        val topTask = placed.shapes.getValue("Task_review")
        val lowerTask = placed.shapes.getValue("Task_verify")
        val finalCrossLaneRoute = placed.edges.getValue("Flow_to_lower")

        assertTrue(topTask.y < lowerTask.y, "lane normalization must place the lower lane below the top lane")
        assertNotEquals(
            topTask.y - rawTopTaskY,
            lowerTask.y - rawLowerTaskY,
            "the cross-lane endpoints must receive different lane-normalization vectors",
        )
        assertNotEquals(
            rawCrossLaneRoute,
            finalCrossLaneRoute,
            "the cross-lane route is replaced after lane normalization instead of retaining its ELK section",
        )
    }

    private fun rawRoute(edge: org.eclipse.elk.graph.ElkEdge): List<BpmnPlacementPass.Point> {
        val section = requireNotNull(edge.sections.firstOrNull()) { "ELK must route the cross-lane sequence flow" }
        val (offsetX, offsetY) = BpmnPlacementPass.edgeContainerOffset(edge)
        return buildList {
            add(BpmnPlacementPass.Point(section.startX + offsetX, section.startY + offsetY))
            section.bendPoints.forEach { add(BpmnPlacementPass.Point(it.x + offsetX, it.y + offsetY)) }
            add(BpmnPlacementPass.Point(section.endX + offsetX, section.endY + offsetY))
        }
    }

    private companion object {
        const val BRANCHED_LANES_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="Collaboration_1">
    <bpmn:participant id="Participant_1" name="Process" processRef="Process_1" />
  </bpmn:collaboration>
  <bpmn:process id="Process_1" isExecutable="true">
    <bpmn:laneSet id="LaneSet_1">
      <bpmn:lane id="Lane_top" name="Top">
        <bpmn:flowNodeRef>Start_1</bpmn:flowNodeRef>
        <bpmn:flowNodeRef>Task_prepare</bpmn:flowNodeRef>
        <bpmn:flowNodeRef>Gateway_split</bpmn:flowNodeRef>
        <bpmn:flowNodeRef>Task_review</bpmn:flowNodeRef>
        <bpmn:flowNodeRef>Gateway_join</bpmn:flowNodeRef>
        <bpmn:flowNodeRef>End_1</bpmn:flowNodeRef>
      </bpmn:lane>
      <bpmn:lane id="Lane_lower" name="Lower">
        <bpmn:flowNodeRef>Task_verify</bpmn:flowNodeRef>
      </bpmn:lane>
    </bpmn:laneSet>
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_start</bpmn:outgoing></bpmn:startEvent>
    <bpmn:serviceTask id="Task_prepare" name="Prepare"><bpmn:incoming>Flow_start</bpmn:incoming><bpmn:outgoing>Flow_decide</bpmn:outgoing></bpmn:serviceTask>
    <bpmn:exclusiveGateway id="Gateway_split"><bpmn:incoming>Flow_decide</bpmn:incoming><bpmn:outgoing>Flow_review</bpmn:outgoing><bpmn:outgoing>Flow_to_lower</bpmn:outgoing></bpmn:exclusiveGateway>
    <bpmn:serviceTask id="Task_review" name="Review"><bpmn:incoming>Flow_review</bpmn:incoming><bpmn:outgoing>Flow_review_join</bpmn:outgoing></bpmn:serviceTask>
    <bpmn:serviceTask id="Task_verify" name="Verify"><bpmn:incoming>Flow_to_lower</bpmn:incoming><bpmn:outgoing>Flow_verify_join</bpmn:outgoing></bpmn:serviceTask>
    <bpmn:exclusiveGateway id="Gateway_join"><bpmn:incoming>Flow_review_join</bpmn:incoming><bpmn:incoming>Flow_verify_join</bpmn:incoming><bpmn:outgoing>Flow_end</bpmn:outgoing></bpmn:exclusiveGateway>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_end</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_start" sourceRef="Start_1" targetRef="Task_prepare" />
    <bpmn:sequenceFlow id="Flow_decide" sourceRef="Task_prepare" targetRef="Gateway_split" />
    <bpmn:sequenceFlow id="Flow_review" sourceRef="Gateway_split" targetRef="Task_review" />
    <bpmn:sequenceFlow id="Flow_to_lower" sourceRef="Gateway_split" targetRef="Task_verify" />
    <bpmn:sequenceFlow id="Flow_review_join" sourceRef="Task_review" targetRef="Gateway_join" />
    <bpmn:sequenceFlow id="Flow_verify_join" sourceRef="Task_verify" targetRef="Gateway_join" />
    <bpmn:sequenceFlow id="Flow_end" sourceRef="Gateway_join" targetRef="End_1" />
  </bpmn:process>
</bpmn:definitions>"""
    }
}
