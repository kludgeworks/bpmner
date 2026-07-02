/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import type { ChipState, StageKey } from "../src/stage-rail"
import { initialStages, reduceStages, STAGE_ORDER } from "../src/stage-rail"

// ---------------------------------------------------------------------------
// Happy-path: all six stages reach done
// ---------------------------------------------------------------------------

describe("reduceStages — happy path", () => {
	it("marks earlier stages done when a later stage becomes active", () => {
		const events = [
			{ stage: "readiness", status: "active" },
			{ stage: "contract", status: "active" },
			{ stage: "generate", status: "active" },
			{ stage: "validate", status: "active" },
			{ stage: "layout", status: "active" },
			{ stage: "align", status: "active" },
			{ stage: "align", status: "done" },
		]

		let state = initialStages()
		for (const ev of events) {
			state = reduceStages(state, ev)
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
		state = reduceStages(state, { stage: "align", status: "active" })

		assert.equal(state.readiness, "done")
		assert.equal(state.contract, "done")
		assert.equal(state.generate, "done")
		assert.equal(state.validate, "done")
		assert.equal(state.layout, "done")
		assert.equal(state.align, "active")
	})
})

// ---------------------------------------------------------------------------
// Repair-loop: validate goes warn, then later stages proceed
// ---------------------------------------------------------------------------

describe("reduceStages — repair loop", () => {
	it("validate goes warn on VALIDATION_FAILED, then proceeds when layout activates", () => {
		const events = [
			{ stage: "readiness", status: "active" },
			{ stage: "contract", status: "active" },
			{ stage: "generate", status: "active" },
			{ stage: "validate", status: "active" },
			{ stage: "validate", status: "warn" }, // repair attempt
			{ stage: "validate", status: "warn" }, // second attempt
			{ stage: "layout", status: "active" },
			{ stage: "align", status: "active" },
			{ stage: "align", status: "done" },
		]

		let state = initialStages()
		for (const ev of events) {
			state = reduceStages(state, ev)
		}

		// All stages end done
		for (const stage of STAGE_ORDER) {
			assert.equal(state[stage], "done", `stage ${stage} should be done`)
		}
	})

	it("warn does not mark earlier stages done", () => {
		let state = initialStages()
		state = reduceStages(state, { stage: "validate", status: "warn" })

		// Only validate is affected
		assert.equal(state.validate, "warn")
		assert.equal(state.readiness, "pending")
		assert.equal(state.contract, "pending")
		assert.equal(state.generate, "pending")
		assert.equal(state.layout, "pending")
		assert.equal(state.align, "pending")
	})
})

// ---------------------------------------------------------------------------
// Vague-input path: stops at readiness active
// ---------------------------------------------------------------------------

describe("reduceStages — vague input", () => {
	it("stops at readiness active when no later stage fires", () => {
		let state = initialStages()
		state = reduceStages(state, { stage: "readiness", status: "active" })

		assert.equal(state.readiness, "active")
		assert.equal(state.contract, "pending")
		assert.equal(state.generate, "pending")
		assert.equal(state.validate, "pending")
		assert.equal(state.layout, "pending")
		assert.equal(state.align, "pending")
	})
})

// ---------------------------------------------------------------------------
// Edge cases: unknown stage/status, idempotency
// ---------------------------------------------------------------------------

describe("reduceStages — edge cases", () => {
	it("ignores unknown stage names (forward-compat)", () => {
		const initial = initialStages()
		const result = reduceStages(initial, {
			stage: "future-stage",
			status: "active",
		})
		assert.deepEqual(result, initial)
	})

	it("ignores unknown status values", () => {
		const initial = initialStages()
		const result = reduceStages(initial, {
			stage: "readiness",
			status: "unknown-status",
		})
		assert.deepEqual(result, initial)
	})

	it("is idempotent — applying same event twice produces same result", () => {
		let state = initialStages()
		state = reduceStages(state, { stage: "validate", status: "active" })
		const once = { ...state }
		state = reduceStages(state, { stage: "validate", status: "active" })
		assert.deepEqual(state, once)
	})
})
