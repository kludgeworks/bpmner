/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal

import dev.groknull.bpmner.layout.BpmnAutoLayoutException
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * A lane carrying a `childLaneSet` (`tLane/childLaneSet`) throws rather than mapping
 * silently: a parent lane with nested lanes carries no `flowNodeRef`s of its own, and
 * without this check its descendants — never claimed by any lane's `flowNodeRefs` — would
 * silently fall through to the top-level process mapping and render as ordinary unlaned
 * nodes. `mapLane` fails loudly instead.
 *
 * Nested lanes are outside the currently supported profile; adding support would require
 * `mapLane` to recurse into `childLaneSet` and `CollaborationFramePlacement.projectLaneBands`
 * to lay out nested bands.
 */
class NestedLaneProbeTest {

    @Test
    fun `a lane with only a childLaneSet fails loudly instead of producing a ghost lane with escaped members`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  id="D1" targetNamespace="https://groknull.dev/bpmner">
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="Participant_1" name="Team" processRef="P1"/>
  </bpmn:collaboration>
  <bpmn:process id="P1" isExecutable="true">
    <bpmn:laneSet id="LS_outer">
      <bpmn:lane id="Lane_region" name="Region">
        <bpmn:childLaneSet id="LS_inner">
          <bpmn:lane id="Lane_team_a" name="Team A">
            <bpmn:flowNodeRef>Start_1</bpmn:flowNodeRef>
          </bpmn:lane>
        </bpmn:childLaneSet>
      </bpmn:lane>
    </bpmn:laneSet>
    <bpmn:startEvent id="Start_1" name="Start">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:endEvent id="End_1" name="End">
      <bpmn:incoming>Flow_1</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="End_1"/>
  </bpmn:process>
</bpmn:definitions>"""

        val ex = assertFailsWith<BpmnAutoLayoutException> {
            ElkBpmnLayouter().apply { registerElkLayoutAlgorithm() }.layout(xml)
        }
        assertContains(ex.message.orEmpty(), "Lane_region")
        assertContains(ex.message.orEmpty(), "nested lanes")
    }
}
