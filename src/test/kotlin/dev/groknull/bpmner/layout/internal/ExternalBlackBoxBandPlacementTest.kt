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
    fun `places black box below white box and reattaches only its terminal`() {
        val root = ElkGraphUtil.createGraph()
        val external = PlacementTestSkeletons.makeNode(root, "External", 10.0, 20.0, 100.0, 60.0)
        val retained = listOf(Point(110.0, 30.0), Point(300.0, 70.0))
        val ctx = PlacementContext(
            PlacementTestSkeletons.parse(XML),
            PlacementTestSkeletons.skeleton(root, mapOf("External" to external)),
            mutableMapOf("Internal" to Rect(200.0, 40.0, 500.0, 180.0), "External" to Rect(10.0, 20.0, 100.0, 60.0)),
            mutableMapOf(),
            mutableMapOf("Message" to listOf(Point(20.0, 30.0)) + retained),
            mutableSetOf(),
        )

        ExternalBlackBoxBandPlacement.process(ctx)

        assertEquals(Rect(200.0, 300.0, 500.0, 60.0), ctx.shapes["External"])
        assertEquals(retained, ctx.edges.getValue("Message").drop(1))
        assertEquals(300.0, ctx.edges.getValue("Message").first().y)
    }

    private companion object {
        const val XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="D" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="C"><bpmn:participant id="Internal" processRef="P"/><bpmn:participant id="External"/><bpmn:messageFlow id="Message" sourceRef="External" targetRef="Task"/></bpmn:collaboration>
  <bpmn:process id="P"><bpmn:serviceTask id="Task"/></bpmn:process>
</bpmn:definitions>"""
    }
}
