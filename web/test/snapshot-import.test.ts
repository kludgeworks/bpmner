/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Tests `hasDiagram` and `importSnapshot` from `../src/snapshot-import`: whether a snapshot
 * carries DI, and how DI-less vs. DI-bearing snapshots (and import failures) are handled.
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { hasDiagram, importSnapshot } from "../src/snapshot-import"

// Fixtures: DI-less XML has no BPMNDiagram element; DI-bearing XML has one.
const DI_LESS_XML =
	'<?xml version="1.0"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" />'
const DI_BEARING_XML =
	'<?xml version="1.0"?><definitions xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"><bpmndi:BPMNDiagram /></definitions>'

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

describe("importSnapshot — DI-less snapshots", () => {
	it("returns pending immediately without calling importXML, forwarding attemptNumber", async () => {
		let importCalled = false
		const deps = {
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
			"importXML must NOT be called for DI-less XML — no client-side layout is attempted",
		)
	})

	it("returns pending without attemptNumber when no attempt is provided", async () => {
		const deps = {
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

describe("importSnapshot — DI-bearing snapshots", () => {
	it("passes DI-bearing XML directly to importXML and returns drawn", async () => {
		let importedXml: string | undefined
		const deps = {
			importXML: async (xml: string) => {
				importedXml = xml
				return { warnings: [] }
			},
		}

		const outcome = await importSnapshot(deps, DI_BEARING_XML)

		assert.equal(outcome.status, "drawn")
		assert.equal(
			importedXml,
			DI_BEARING_XML,
			"importXML should receive the original DI-bearing XML unchanged",
		)
	})

	it("returns pending when importXML throws, preserving the prior drawing", async () => {
		const deps = {
			importXML: async (_xml: string) => {
				throw new Error("no diagram to display")
			},
		}

		const outcome = await importSnapshot(deps, DI_BEARING_XML)

		assert.equal(outcome.status, "pending")
	})
})
