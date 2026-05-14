import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { getRuleCapabilities } from "../src/linter-bundle"

describe("getRuleCapabilities", () => {
	it("returns a non-empty array", () => {
		const caps = getRuleCapabilities()
		assert.ok(Array.isArray(caps))
		assert.ok(caps.length > 0)
	})

	it("each capability has the required fields", () => {
		const caps = getRuleCapabilities()
		for (const cap of caps) {
			assert.ok(
				typeof cap.id === "string" && cap.id.length > 0,
				`id missing on ${JSON.stringify(cap)}`,
			)
			assert.ok(
				typeof cap.severity === "string",
				`severity missing on ${cap.id}`,
			)
			assert.ok(typeof cap.kind === "string", `kind missing on ${cap.id}`)
			assert.ok(typeof cap.safety === "string", `safety missing on ${cap.id}`)
			assert.ok(
				typeof cap.handlerExists === "boolean",
				`handlerExists missing on ${cap.id}`,
			)
		}
	})

	it("LOCAL_XML_FIX rule act-activity-label-capitalization has correct capability", () => {
		const caps = getRuleCapabilities()
		const cap = caps.find((c) => c.id === "act-activity-label-capitalization")
		assert.ok(
			cap,
			"act-activity-label-capitalization not found in capabilities",
		)
		assert.equal(cap.kind, "LOCAL_XML_FIX")
		assert.equal(cap.safety, "SAFE_AUTOMATIC")
		assert.equal(cap.handlerName, "fixSentenceCase")
		assert.equal(cap.handlerExists, true)
	})

	it("LOCAL_MODEL_FIX rule gtw-fake-join routes to Kotlin handler", () => {
		const caps = getRuleCapabilities()
		const cap = caps.find((c) => c.id === "gtw-fake-join")
		assert.ok(cap, "gtw-fake-join not found in capabilities")
		assert.equal(cap.kind, "LOCAL_MODEL_FIX")
		assert.equal(cap.handlerName, "insertConvergingGateway")
		// handlerExists is false from the TS bundle perspective: LOCAL_MODEL_FIX
		// handlers live in the Kotlin handler registry, not the TS auto-fix registry.
		assert.equal(cap.handlerExists, false)
	})

	it("LLM_MODEL_PATCH rule name-business-meaningful-label has correct capability", () => {
		const caps = getRuleCapabilities()
		const cap = caps.find((c) => c.id === "name-business-meaningful-label")
		assert.ok(cap, "name-business-meaningful-label not found in capabilities")
		assert.equal(cap.kind, "LLM_MODEL_PATCH")
		assert.equal(cap.handlerName, null)
		assert.equal(cap.handlerExists, false)
	})

	it("LOCAL_XML_FIX rule with replacementMap has it populated", () => {
		const caps = getRuleCapabilities()
		const cap = caps.find((c) => c.id === "name-uncommon-abbreviations")
		assert.ok(cap, "name-uncommon-abbreviations not found in capabilities")
		assert.equal(cap.kind, "LOCAL_XML_FIX")
		assert.ok(cap.replacementMap !== null, "replacementMap should be present")
		assert.ok(
			typeof cap.replacementMap === "object",
			"replacementMap should be an object",
		)
	})

	it("LLM rules have handlerExists: false", () => {
		const caps = getRuleCapabilities()
		const llmCaps = caps.filter(
			(c) => c.kind === "LLM_MODEL_PATCH" || c.kind === "LLM_XML_REWRITE",
		)
		assert.ok(llmCaps.length > 0, "expected at least one LLM rule")
		for (const cap of llmCaps) {
			assert.equal(
				cap.handlerExists,
				false,
				`LLM rule ${cap.id} should not have a handler`,
			)
			assert.equal(
				cap.handlerName,
				null,
				`LLM rule ${cap.id} should have null handlerName`,
			)
		}
	})

	it("UNFIXABLE rules have handlerExists: false", () => {
		const caps = getRuleCapabilities()
		const unfixable = caps.filter((c) => c.kind === "UNFIXABLE")
		for (const cap of unfixable) {
			assert.equal(
				cap.handlerExists,
				false,
				`UNFIXABLE rule ${cap.id} should not have a handler`,
			)
		}
	})

	it("all LOCAL_XML_FIX rules have registered handlers", () => {
		const caps = getRuleCapabilities()
		const localXml = caps.filter((c) => c.kind === "LOCAL_XML_FIX")
		assert.ok(localXml.length > 0, "expected at least one LOCAL_XML_FIX rule")
		for (const cap of localXml) {
			assert.equal(
				cap.handlerExists,
				true,
				`LOCAL_XML_FIX rule ${cap.id} declares handler "${cap.handlerName}" but it is not registered`,
			)
		}
	})

	it("no-overlapping-elements is flagged layoutSensitive=true", () => {
		const caps = getRuleCapabilities()
		const cap = caps.find((c) => c.id === "no-overlapping-elements")
		assert.ok(cap, "no-overlapping-elements not found in capabilities")
		assert.equal(cap.layoutSensitive, true)
	})

	it("KLM rules default to layoutSensitive=false", () => {
		const caps = getRuleCapabilities()
		const cap = caps.find((c) => c.id === "name-uncommon-abbreviations")
		assert.ok(cap, "name-uncommon-abbreviations not found in capabilities")
		assert.equal(cap.layoutSensitive, false)
	})
})
