/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Rect
import dev.groknull.bpmner.layout.internal.placement.ExternalBlackBoxBandPlacement
import dev.groknull.bpmner.layout.internal.placement.PlacementContext
import org.eclipse.elk.graph.util.ElkGraphUtil
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ExternalBlackBoxBandPlacementTest {

    @Test
    fun `regenerates a direct route from the fixed task to the relocated band`() {
        val ctx = collaborationContext()

        ExternalBlackBoxBandPlacement.process(ctx)

        assertEquals(Rect(200.0, 300.0, 500.0, 60.0), ctx.shapes["External"])
        assertEquals(listOf(Point(450.0, 160.0), Point(450.0, 300.0)), ctx.edges["Message"])
        val (width, height) = BpmnPlacementPass.estimateLabelDimensions("Request", BpmnPlacementPass.EDGE_LABEL_WIDTH)
        assertEquals(Rect(450.0 - width / 2.0, 230.0 - height / 2.0, width, height), ctx.labels["Message"])
    }

    @Test
    fun `regenerates a direct route from the relocated band to the fixed task`() {
        val ctx = collaborationContext(source = "External", target = "Task")

        ExternalBlackBoxBandPlacement.process(ctx)

        assertEquals(listOf(Point(450.0, 300.0), Point(450.0, 160.0)), ctx.edges["Message"])
    }

    @Test
    fun `leaves a message flow it does not reattach byte-for-byte unchanged`() {
        val ctx = collaborationContext()
        val otherRoute = listOf(Point(1.0, 2.0), Point(3.0, 4.0), Point(5.0, 6.0))
        val otherLabel = Rect(7.0, 8.0, 90.0, 20.0)
        ctx.edges["Other"] = otherRoute
        ctx.labels["Other"] = otherLabel

        ExternalBlackBoxBandPlacement.process(ctx)

        assertEquals(otherRoute, ctx.edges["Other"])
        assertEquals(otherLabel, ctx.labels["Other"])
    }

    private fun collaborationContext(source: String = "Task", target: String = "External"): PlacementContext {
        val root = ElkGraphUtil.createGraph()
        val external = PlacementTestSkeletons.makeNode(root, "External", 10.0, 20.0, 100.0, 60.0)
        return PlacementContext(
            PlacementTestSkeletons.parse(xml(source, target)),
            PlacementTestSkeletons.skeleton(root, mapOf("External" to external)),
            mutableMapOf(
                "Internal" to Rect(200.0, 40.0, 500.0, 180.0),
                "External" to Rect(10.0, 20.0, 100.0, 60.0),
                "Task" to Rect(400.0, 80.0, 100.0, 80.0),
                "Task2" to Rect(300.0, 80.0, 100.0, 80.0),
            ),
            mutableMapOf("Message" to Rect(0.0, 0.0, 90.0, 20.0)),
            mutableMapOf("Message" to listOf(Point(0.0, 0.0), Point(1.0, 1.0), Point(2.0, 2.0))),
            mutableSetOf(),
        )
    }

    private fun xml(source: String, target: String) = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C">
    <bpmn:participant id="Internal" processRef="P"/><bpmn:participant id="External"/>
    <bpmn:messageFlow id="Message" name="Request" sourceRef="$source" targetRef="$target"/>
    <bpmn:messageFlow id="Other" sourceRef="Task" targetRef="Task2"/>
  </bpmn:collaboration>
  <bpmn:process id="P"><bpmn:serviceTask id="Task"/><bpmn:serviceTask id="Task2"/></bpmn:process>
</bpmn:definitions>"""
}
