/**
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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

	it("rejects on invalid XML", async () => {
		await assert.rejects(() => api.layoutXml("not valid xml"))
	})
})
