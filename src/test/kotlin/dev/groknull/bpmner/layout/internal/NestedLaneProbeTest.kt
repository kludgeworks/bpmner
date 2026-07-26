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
 * L5 spike (622-Y): what is the current stance on nested lanes (`tLane/childLaneSet`, XSD
 * `Semantic.xsd:941`)? Nothing in `src/main` referenced `childLaneSet` before this probe.
 *
 * **Verdict: nested lanes are unsupported, and before this probe that failed *silently* —
 * a real defect this probe fixes rather than merely records.** A lane that delegates to a
 * `childLaneSet` (the BPMN-conformant shape: a parent lane with nested lanes carries no
 * `flowNodeRef`s of its own) mapped to a zero-height ghost `ElkNode` compound, while its
 * descendants (never claimed by any lane's `flowNodeRefs`) silently fell through to the
 * top-level process mapping and rendered as ordinary unlaned nodes — a broken diagram with
 * no error, confirmed empirically on the fixture below before the fix. `mapLane` now
 * throws when a lane carries a `childLaneSet`, converting a silent bad diagram into a loud,
 * clear failure (AD-622-10's "assert, don't mutate" applied to a construct this epic never
 * implements, not one it renders wrong).
 *
 * Nested lanes are out of this epic's supported profile — not named as a goal in the epic
 * issue, and #591's lane work never claimed it. Should nested-lane support become a real
 * requirement, this probe is the starting point: `mapLane` needs to recurse into
 * `childLaneSet` and `CollaborationShapePlacement.projectLaneBands` needs a nested-band
 * layout, neither of which this epic scopes.
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
