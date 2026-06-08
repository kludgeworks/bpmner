/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { before, describe, it } from "node:test"

type BpmnLayoutApi = {
	layoutXml(xml: string): Promise<string>
}

const MINIMAL_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             id="Definitions_1"
             targetNamespace="http://example.com">
  <process id="Process_1" isExecutable="false">
    <startEvent id="StartEvent_1" />
    <endEvent id="EndEvent_1" />
    <sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="EndEvent_1" />
  </process>
</definitions>`

// Single-pool process partitioned into two role lanes. The auto-layout library
// must place lane-partitioned flow nodes without throwing or dropping the laneSet.
const LANES_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             id="Definitions_lanes"
             targetNamespace="http://example.com">
  <process id="Process_lanes" isExecutable="false">
    <laneSet id="LaneSet_1">
      <lane id="Lane_support" name="Customer support">
        <flowNodeRef>StartEvent_1</flowNodeRef>
        <flowNodeRef>Task_classify</flowNodeRef>
      </lane>
      <lane id="Lane_finance" name="Finance">
        <flowNodeRef>Task_refund</flowNodeRef>
        <flowNodeRef>EndEvent_1</flowNodeRef>
      </lane>
    </laneSet>
    <startEvent id="StartEvent_1" />
    <userTask id="Task_classify" name="Classify request" />
    <serviceTask id="Task_refund" name="Execute refund" />
    <endEvent id="EndEvent_1" />
    <sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_classify" />
    <sequenceFlow id="Flow_2" sourceRef="Task_classify" targetRef="Task_refund" />
    <sequenceFlow id="Flow_3" sourceRef="Task_refund" targetRef="EndEvent_1" />
  </process>
</definitions>`

// Two-participant collaboration with one message flow between the pools. The
// auto-layout library must place both participants and route the message flow without throwing.
const COLLABORATION_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             id="Definitions_collab"
             targetNamespace="http://example.com">
  <collaboration id="Collaboration_1">
    <participant id="Participant_buyer" name="Buyer" processRef="Process_buyer" />
    <participant id="Participant_supplier" name="Supplier" processRef="Process_supplier" />
    <messageFlow id="MsgFlow_1" sourceRef="Task_issue_po" targetRef="Task_intake" />
  </collaboration>
  <process id="Process_buyer" isExecutable="false">
    <startEvent id="Start_buyer" />
    <task id="Task_issue_po" name="Issue purchase order" />
    <endEvent id="End_buyer" />
    <sequenceFlow id="BF1" sourceRef="Start_buyer" targetRef="Task_issue_po" />
    <sequenceFlow id="BF2" sourceRef="Task_issue_po" targetRef="End_buyer" />
  </process>
  <process id="Process_supplier" isExecutable="false">
    <startEvent id="Start_supplier" />
    <task id="Task_intake" name="Receive order" />
    <endEvent id="End_supplier" />
    <sequenceFlow id="SF1" sourceRef="Start_supplier" targetRef="Task_intake" />
    <sequenceFlow id="SF2" sourceRef="Task_intake" targetRef="End_supplier" />
  </process>
</definitions>`

describe("BpmnLayoutApi bundle smoke test", () => {
	let api: BpmnLayoutApi

	before(() => {
		require("../src/layout-bundle")
		const globalApi = (
			globalThis as typeof globalThis & { BpmnLayoutApi?: BpmnLayoutApi }
		).BpmnLayoutApi
		assert.ok(globalApi, "BpmnLayoutApi should be defined on globalThis")
		if (!globalApi) {
			throw new Error("BpmnLayoutApi should be defined on globalThis")
		}
		api = globalApi
	})

	it("exposes layoutXml as a function", () => {
		assert.equal(typeof api.layoutXml, "function")
	})

	it("returns layouted XML for a minimal process", async () => {
		const result = await api.layoutXml(MINIMAL_BPMN)
		assert.ok(
			typeof result === "string" && result.length > 0,
			"result should be a non-empty string",
		)
		assert.ok(
			result.includes("<definitions"),
			"result should contain BPMN definitions",
		)
	})

	it("lays out a lane-partitioned process and preserves the laneSet", async () => {
		const result = await api.layoutXml(LANES_BPMN)
		assert.ok(
			typeof result === "string" && result.length > 0,
			"result should be a non-empty string",
		)
		assert.ok(
			result.includes("<laneSet") || result.includes(":laneSet"),
			"laneSet must survive layout",
		)
		assert.ok(
			result.includes("BPMNShape") || result.includes("BPMNDiagram"),
			"layout should add diagram interchange",
		)
	})

	it("lays out a two-pool collaboration with a message flow", async () => {
		const result = await api.layoutXml(COLLABORATION_BPMN)
		assert.ok(
			typeof result === "string" && result.length > 0,
			"result should be a non-empty string",
		)
		assert.ok(
			result.includes("<collaboration") || result.includes(":collaboration"),
			"collaboration must survive layout",
		)
		assert.ok(
			result.includes("<participant") || result.includes(":participant"),
			"participants must survive layout",
		)
		assert.ok(
			result.includes("BPMNShape") || result.includes("BPMNDiagram"),
			"layout should add diagram interchange",
		)
	})

	it("rejects on invalid XML", async () => {
		await assert.rejects(() => api.layoutXml("not valid xml"))
	})
})
