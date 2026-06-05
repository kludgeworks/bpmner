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

// Single-pool process partitioned into two role lanes (the #187 lanes case). The auto-layout
// library must place lane-partitioned flow nodes without throwing or dropping the laneSet.
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

	it("rejects on invalid XML", async () => {
		await assert.rejects(() => api.layoutXml("not valid xml"))
	})
})
