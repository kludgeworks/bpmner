/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { hasDiagram, importSnapshot } from "../src/snapshot-import"

// Fixtures: DI-less XML has no BPMNDiagram element; DI-bearing XML has one.
const DI_LESS_XML =
	'<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" />'
const DI_BEARING_XML =
	'<?xml version="1.0"?><definitions xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"><bpmndi:BPMNDiagram /></definitions>'
const LAID_OUT_XML =
	'<?xml version="1.0"?><definitions xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"><bpmndi:BPMNDiagram><bpmndi:BPMNPlane /></bpmndi:BPMNDiagram></definitions>'

// -------------------------------------------------------------------------
// hasDiagram
// -------------------------------------------------------------------------

describe("hasDiagram", () => {
	it("returns false for DI-less XML", () => {
		assert.equal(hasDiagram(DI_LESS_XML), false)
	})

	it("returns true for DI-bearing XML containing bpmndi:BPMNDiagram", () => {
		assert.equal(hasDiagram(DI_BEARING_XML), true)
	})

	it("returns true when BPMNDiagram appears with any namespace prefix", () => {
		assert.equal(hasDiagram("<BPMNDiagram />"), true)
		assert.equal(hasDiagram("<bpmndi:BPMNDiagram />"), true)
	})

	it("returns false for empty string", () => {
		assert.equal(hasDiagram(""), false)
	})
})

// -------------------------------------------------------------------------
// importSnapshot — happy paths
// -------------------------------------------------------------------------

describe("importSnapshot — happy paths", () => {
	it("calls layout then importXML for DI-less XML and returns drawn", async () => {
		let layoutedXml: string | undefined
		let importedXml: string | undefined
		const deps = {
			layout: async (xml: string) => {
				layoutedXml = xml
				return LAID_OUT_XML
			},
			importXML: async (xml: string) => {
				importedXml = xml
				return { warnings: [] }
			},
		}

		const outcome = await importSnapshot(deps, DI_LESS_XML, 1)

		assert.equal(outcome.status, "drawn")
		assert.equal(
			layoutedXml,
			DI_LESS_XML,
			"layout should receive the original DI-less XML",
		)
		assert.equal(
			importedXml,
			LAID_OUT_XML,
			"importXML should receive the laid-out result",
		)
	})

	it("skips layout and passes DI-bearing XML directly to importXML", async () => {
		let layoutCalled = false
		let importedXml: string | undefined
		const deps = {
			layout: async (_xml: string) => {
				layoutCalled = true
				return LAID_OUT_XML
			},
			importXML: async (xml: string) => {
				importedXml = xml
				return { warnings: [] }
			},
		}

		const outcome = await importSnapshot(deps, DI_BEARING_XML)

		assert.equal(outcome.status, "drawn")
		assert.equal(
			layoutCalled,
			false,
			"layout must NOT be called for DI-bearing XML",
		)
		assert.equal(
			importedXml,
			DI_BEARING_XML,
			"importXML should receive the original DI-bearing XML",
		)
	})
})

// -------------------------------------------------------------------------
// importSnapshot — fallback paths (ARCH ADR-ss-007 fallback rule)
// -------------------------------------------------------------------------

describe("importSnapshot — fallback paths", () => {
	it("returns pending with attemptNumber when layout throws (broken interim XML)", async () => {
		let importCalled = false
		const deps = {
			layout: async (_xml: string) => {
				throw new Error("layouter: orphan node reference")
			},
			importXML: async (_xml: string) => {
				importCalled = true
				return { warnings: [] }
			},
		}

		const outcome = await importSnapshot(deps, DI_LESS_XML, 3)

		assert.equal(outcome.status, "pending")
		assert.equal(
			(outcome as { status: "pending"; attemptNumber?: number }).attemptNumber,
			3,
			"attemptNumber should be forwarded",
		)
		assert.equal(
			importCalled,
			false,
			"importXML must NOT be called when layout throws",
		)
	})

	it("returns pending when importXML throws (viewer rejects the XML)", async () => {
		const deps = {
			layout: async (_xml: string) => LAID_OUT_XML,
			importXML: async (_xml: string) => {
				throw new Error("no diagram to display")
			},
		}

		const outcome = await importSnapshot(deps, DI_LESS_XML)

		assert.equal(outcome.status, "pending")
	})

	it("returns pending without attemptNumber when no attempt is provided", async () => {
		const deps = {
			layout: async (_xml: string) => {
				throw new Error("broken")
			},
			importXML: async (_xml: string) => ({ warnings: [] }),
		}

		const outcome = await importSnapshot(deps, DI_LESS_XML)

		assert.equal(outcome.status, "pending")
		assert.equal(
			(outcome as { status: "pending"; attemptNumber?: number }).attemptNumber,
			undefined,
		)
	})
})
