/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * V6 (622-Y, AD-622-35): a floating element (no incoming/outgoing sequence flow to its
 * enclosing scope — an event subprocess or a compensation-handler task) becomes its own ELK
 * component. `SEPARATE_CONNECTED_COMPONENTS = true` already guarantees it never overlaps the
 * main flow; `CONSIDER_MODEL_ORDER_COMPONENTS = MODEL_ORDER` (`BpmnToElkMapper.kt`) additionally
 * orders the packer's rows by BPMN declaration order, so the main flow — declared first — sets
 * row 1 and the floating element wraps to the row below it.
 *
 * Live only on the flat (non-collaboration) path — the root's `HIERARCHY_HANDLING` is
 * `SEPARATE_CHILDREN`, so a `process` fixture exercises it; `applyParticipantProfile`/
 * `applyLaneProfile` explicitly disable `SEPARATE_CONNECTED_COMPONENTS` for compound hosts, so
 * this option is inert for collaborations.
 *
 * The rule this asserts: a floating component's top lies below the bottom of the component
 * preceding it in model order.
 */
class FloatingElementAnchorProbeTest {

    @Test
    fun `a floating event subprocess is placed below the main flow, in model order`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:error id="Error_1" errorCode="ERR"/>
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1"/>
    <bpmn:serviceTask id="Task_1" name="Do the work"/>
    <bpmn:endEvent id="EndEvent_1"/>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1"/>
    <bpmn:subProcess id="EventSub_1" triggeredByEvent="true">
      <bpmn:startEvent id="EventSub_start">
        <bpmn:errorEventDefinition errorRef="Error_1"/>
      </bpmn:startEvent>
      <bpmn:endEvent id="EventSub_end"/>
      <bpmn:sequenceFlow id="Flow_inner" sourceRef="EventSub_start" targetRef="EventSub_end"/>
    </bpmn:subProcess>
  </bpmn:process>
</bpmn:definitions>"""

        val output = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(output)

        val mainFlowBounds = listOf("StartEvent_1", "Task_1", "EndEvent_1").map { LayoutDiInspector.shapeBounds(doc, it) }
        val handlerBounds = LayoutDiInspector.shapeBounds(doc, "EventSub_1")

        assertNoOverlap(mainFlowBounds, handlerBounds)
        assertBelow(mainFlowBounds, handlerBounds)
    }

    @Test
    fun `a floating compensation-handler task is placed below the main flow, in model order`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1"/>
    <bpmn:serviceTask id="Task_1" name="Do the work"/>
    <bpmn:endEvent id="EndEvent_1"/>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1"/>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1"/>
    <bpmn:userTask id="Task_refund" name="Refund payment" isForCompensation="true"/>
  </bpmn:process>
</bpmn:definitions>"""

        val output = ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        val doc = LayoutDiInspector.parse(output)

        val mainFlowBounds = listOf("StartEvent_1", "Task_1", "EndEvent_1").map { LayoutDiInspector.shapeBounds(doc, it) }
        val handlerBounds = LayoutDiInspector.shapeBounds(doc, "Task_refund")

        assertNoOverlap(mainFlowBounds, handlerBounds)
        assertBelow(mainFlowBounds, handlerBounds)
    }

    // The invariant SEPARATE_CONNECTED_COMPONENTS guarantees: distinct components never share
    // screen space. Two axis-aligned rectangles overlap iff their x- and y-ranges both overlap;
    // no overlap on either axis is sufficient to rule out a collision.
    private fun assertNoOverlap(others: List<Map<String, Double>>, handler: Map<String, Double>) {
        others.forEach { other ->
            val xOverlap =
                handler["x"]!! < other["x"]!! + other["width"]!! &&
                    other["x"]!! < handler["x"]!! + handler["width"]!!
            val yOverlap =
                handler["y"]!! < other["y"]!! + other["height"]!! &&
                    other["y"]!! < handler["y"]!! + handler["height"]!!
            assertTrue(!(xOverlap && yOverlap), "floating element $handler must not overlap main-flow node $other")
        }
    }

    // AD-622-35: the model-order rule. The main flow is declared first, so the floating
    // component (declared after it) must land below the main flow's bottom edge.
    private fun assertBelow(precedingInModelOrder: List<Map<String, Double>>, floating: Map<String, Double>) {
        val precedingBottom = precedingInModelOrder.maxOf { it["y"]!! + it["height"]!! }
        assertTrue(
            floating["y"]!! >= precedingBottom,
            "expected the floating component to sit below the main flow " +
                "(top=${floating["y"]}, main flow bottom=$precedingBottom)",
        )
    }
}
