/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import type { RunUpdate } from "../src/run-update"
import type { ChipState, StageKey } from "../src/stage-rail"
import { initialStages, reduceStages, STAGE_ORDER } from "../src/stage-rail"

function progress(
	phase: RunUpdate["phase"],
	artifactState: RunUpdate["artifactState"] = "NONE",
): RunUpdate {
	return { seq: 1, phase, artifactState, summary: "s" }
}

function terminal(outcome: RunUpdate["outcome"] = "COMPLETED"): RunUpdate {
	return {
		seq: 99,
		phase: "FINISHED",
		artifactState: "FINAL",
		summary: "done",
		outcome,
	}
}

// ---------------------------------------------------------------------------
// Happy-path: all six stages reach done
// ---------------------------------------------------------------------------

describe("reduceStages — happy path", () => {
	it("marks earlier stages done when a later stage becomes active", () => {
		const updates: RunUpdate[] = [
			progress("READINESS"),
			progress("CONTRACT"),
			progress("OUTLINE"),
			progress("VALIDATION"),
			progress("LAYOUT"),
			progress("ALIGNMENT"),
			terminal(),
		]

		let state = initialStages()
		for (const update of updates) {
			state = reduceStages(state, update)
		}

		const expected: Record<StageKey, ChipState> = {
			readiness: "done",
			contract: "done",
			generate: "done",
			validate: "done",
			layout: "done",
			align: "done",
		}
		assert.deepEqual(state, expected)
	})

	it("marks all earlier stages done when the final stage activates", () => {
		let state = initialStages()
		state = reduceStages(state, progress("ALIGNMENT"))

		assert.equal(state.readiness, "done")
		assert.equal(state.contract, "done")
		assert.equal(state.generate, "done")
		assert.equal(state.validate, "done")
		assert.equal(state.layout, "done")
		assert.equal(state.align, "active")
	})
})

// ---------------------------------------------------------------------------
// Repair-loop: validate goes warn on a DIAGNOSTIC artifact state, then proceeds
// ---------------------------------------------------------------------------

describe("reduceStages — repair loop", () => {
	it("validate goes warn on a DIAGNOSTIC artifactState, then proceeds when layout activates", () => {
		const updates: RunUpdate[] = [
			progress("READINESS"),
			progress("CONTRACT"),
			progress("DRAFT", "XML_DRAFT"),
			progress("VALIDATION", "DIAGNOSTIC"), // repair attempt
			progress("VALIDATION", "DIAGNOSTIC"), // second attempt
			progress("VALIDATION", "XML_DRAFT"), // passed
			progress("LAYOUT", "XML_DRAFT"),
			progress("ALIGNMENT", "XML_DRAFT"),
			terminal(),
		]

		let state = initialStages()
		for (const update of updates) {
			state = reduceStages(state, update)
		}

		for (const stage of STAGE_ORDER) {
			assert.equal(state[stage], "done", `stage ${stage} should be done`)
		}
	})

	it("warn does not mark earlier stages done", () => {
		let state = initialStages()
		state = reduceStages(state, progress("VALIDATION", "DIAGNOSTIC"))

		assert.equal(state.validate, "warn")
		assert.equal(state.readiness, "pending")
		assert.equal(state.contract, "pending")
		assert.equal(state.generate, "pending")
		assert.equal(state.layout, "pending")
		assert.equal(state.align, "pending")
	})
})

// ---------------------------------------------------------------------------
// Vague-input / NEEDS_CLARIFICATION path: stops at readiness, then terminates
// ---------------------------------------------------------------------------

describe("reduceStages — vague input", () => {
	it("stops at readiness active when no later stage fires", () => {
		let state = initialStages()
		state = reduceStages(state, progress("READINESS"))

		assert.equal(state.readiness, "active")
		assert.equal(state.contract, "pending")
		assert.equal(state.generate, "pending")
		assert.equal(state.validate, "pending")
		assert.equal(state.layout, "pending")
		assert.equal(state.align, "pending")
	})

	it("a terminal update leaves never-reached stages pending, not done", () => {
		let state = initialStages()
		state = reduceStages(state, progress("READINESS"))
		state = reduceStages(state, terminal("FAILED"))

		assert.equal(state.readiness, "done")
		assert.equal(state.contract, "pending")
		assert.equal(state.generate, "pending")
		assert.equal(state.validate, "pending")
		assert.equal(state.layout, "pending")
		assert.equal(state.align, "pending")
	})
})

// ---------------------------------------------------------------------------
// Edge cases: unmapped phases, idempotency
// ---------------------------------------------------------------------------

describe("reduceStages — edge cases", () => {
	it("ignores phases with no stage mapping (forward-compat)", () => {
		const initial = initialStages()
		const result = reduceStages(initial, progress("AWAITING_INPUT"))
		assert.deepEqual(result, initial)
	})

	it("is idempotent — applying the same update twice produces the same result", () => {
		let state = initialStages()
		const update = progress("VALIDATION")
		state = reduceStages(state, update)
		const once = { ...state }
		state = reduceStages(state, update)
		assert.deepEqual(state, once)
	})
})
