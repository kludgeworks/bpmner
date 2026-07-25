/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * V6 probe (622-Y, AD-622-09): does the existing `SEPARATE_CONNECTED_COMPONENTS` mechanism
 * already anchor a floating element (no incoming/outgoing sequence flow to its enclosing
 * scope — an event subprocess or a compensation-handler task) below the main flow, or does
 * it need a dedicated placement processor?
 *
 * `applyRootLayoutOptions` (`BpmnToElkMapper.kt`) sets `SEPARATE_CONNECTED_COMPONENTS = true`
 * on the root, previously commented "Handler nodes (no incoming ELK edge) become disconnected
 * components placed below." Live only on the flat (non-collaboration) path — the root's
 * `HIERARCHY_HANDLING` is `SEPARATE_CHILDREN` (`:537`), so a `process` fixture exercises it;
 * `applyParticipantProfile`/`applyLaneProfile` explicitly disable it (`:561`, `:576`) for
 * compound hosts.
 *
 * **Verdict (falsifies the "placed below" half of the prior comment): the anchor is a partial
 * declaration.** ELK's component packer guarantees the floating element never overlaps the
 * main flow's bounding box — verified below — but it does **not** guarantee a "below the main
 * flow" *order*: on this fixture the floating component lands at (x=12, y=12), strictly
 * *above* the main flow (whose top edge sits at y=208), not below it. `ComponentsProcessor`
 * places components by its own packing heuristic, which this codebase does not steer.
 *
 * This is a real, documented gap (AD-622-09's "or a processor with the documented gap" floor),
 * not the preferred "declaration already does it" outcome — recording the verdict as an ADR
 * (whether "non-overlapping, unordered" is an acceptable floor, or a placement processor must
 * force "below") is the architect's call, not this session's.
 */
class FloatingElementAnchorProbeTest {

    @Test
    fun `a floating event subprocess does not overlap the main flow, but is not placed below it`() {
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

        // Documents the falsified half of the old comment: it does NOT land below. Regenerate
        // this assertion's recorded coordinates (not the invariant above) if a future placement
        // processor changes the packer's ordering — that is the point at which V6 re-opens.
        val mainFlowTop = mainFlowBounds.minOf { it["y"]!! }
        assertTrue(
            handlerBounds["y"]!! < mainFlowTop,
            "expected the documented gap to still hold (floating component above the main flow, " +
                "not below) — if this now sits below (y=${handlerBounds["y"]} >= top=$mainFlowTop), " +
                "the packer's ordering has changed and V6's verdict should be re-probed",
        )
    }

    @Test
    fun `a floating compensation-handler task does not overlap the main flow, but is not placed below it`() {
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
    }

    // The one invariant SEPARATE_CONNECTED_COMPONENTS does guarantee: distinct components never
    // share screen space. Two axis-aligned rectangles overlap iff their x- and y-ranges both
    // overlap; no overlap on either axis is sufficient to rule out a collision.
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
}
