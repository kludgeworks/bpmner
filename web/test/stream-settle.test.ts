/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	initialSettle,
	type SettleState,
	shouldClose,
} from "../src/stream-settle"

// ---------------------------------------------------------------------------
// shouldClose — the three-gate condition (REVIEW-ss-3 F1 regression guard)
// ---------------------------------------------------------------------------

describe("shouldClose — requires all three signals", () => {
	it("returns false for initial state (nothing seen)", () => {
		assert.equal(shouldClose(initialSettle()), false)
	})

	it("returns false when only finish seen", () => {
		const s: SettleState = { sawFinish: true, sawCost: false, sawResult: false }
		assert.equal(shouldClose(s), false)
	})

	it("returns false when only cost seen", () => {
		const s: SettleState = { sawFinish: false, sawCost: true, sawResult: false }
		assert.equal(shouldClose(s), false)
	})

	it("returns false when only result seen", () => {
		const s: SettleState = { sawFinish: false, sawCost: false, sawResult: true }
		assert.equal(shouldClose(s), false)
	})

	it("returns false when finish + cost but NOT result — the F1 race", () => {
		// This is the exact ordering the bug produced: finish and cost arrive,
		// result has not yet — under the old gate this would have closed the stream.
		const s: SettleState = { sawFinish: true, sawCost: true, sawResult: false }
		assert.equal(shouldClose(s), false)
	})

	it("returns false when finish + result but NOT cost", () => {
		const s: SettleState = { sawFinish: true, sawCost: false, sawResult: true }
		assert.equal(shouldClose(s), false)
	})

	it("returns false when cost + result but NOT finish", () => {
		const s: SettleState = { sawFinish: false, sawCost: true, sawResult: true }
		assert.equal(shouldClose(s), false)
	})

	it("returns true only when all three are seen", () => {
		const s: SettleState = { sawFinish: true, sawCost: true, sawResult: true }
		assert.equal(shouldClose(s), true)
	})
})

// ---------------------------------------------------------------------------
// initialSettle — reset contract
// ---------------------------------------------------------------------------

describe("initialSettle", () => {
	it("returns all-false state", () => {
		const s = initialSettle()
		assert.equal(s.sawFinish, false)
		assert.equal(s.sawCost, false)
		assert.equal(s.sawResult, false)
	})

	it("returns a new object each call (not a shared singleton)", () => {
		const a = initialSettle()
		const b = initialSettle()
		a.sawFinish = true
		assert.equal(b.sawFinish, false)
	})
})
