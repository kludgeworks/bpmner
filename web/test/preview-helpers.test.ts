/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { formatError, parsePreviewXml } from "../src/preview-helpers"

describe("parsePreviewXml", () => {
	it("parses a JSON-encoded BPMN XML string", () => {
		const xml = "<?xml version='1.0'?><definitions />"
		const textContent = JSON.stringify(xml)

		assert.equal(parsePreviewXml(textContent), xml)
	})

	it("returns empty string when textContent is null", () => {
		assert.equal(parsePreviewXml(null), "")
	})

	it("returns empty string when textContent is the JSON null value", () => {
		// '""' decodes to ""
		assert.equal(parsePreviewXml('""'), "")
	})

	it("round-trips BPMN XML with special characters", () => {
		const xml = '<definitions name="a &lt; b &amp; c">'
		const textContent = JSON.stringify(xml)

		assert.equal(parsePreviewXml(textContent), xml)
	})
})

describe("formatError", () => {
	it("returns message for Error instances", () => {
		const error = new Error("something went wrong")

		assert.equal(formatError(error), "something went wrong")
	})

	it("returns String() for non-Error values", () => {
		assert.equal(formatError("oops"), "oops")
		assert.equal(formatError(42), "42")
		assert.equal(formatError(null), "null")
		assert.equal(formatError(undefined), "undefined")
	})

	it("returns String() for plain objects", () => {
		assert.equal(formatError({ code: 500 }), "[object Object]")
	})
})
