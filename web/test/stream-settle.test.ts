/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import type { RunUpdate } from "../src/run-update"
import { shouldClose } from "../src/stream-settle"

function progress(overrides: Partial<RunUpdate> = {}): RunUpdate {
	return {
		seq: 1,
		phase: "READINESS",
		artifactState: "NONE",
		summary: "s",
		...overrides,
	}
}

describe("shouldClose", () => {
	it("returns false for a non-terminal update", () => {
		assert.equal(shouldClose(progress()), false)
	})

	it("returns false for an AWAITING_INPUT update (mid-run, not terminal)", () => {
		assert.equal(shouldClose(progress({ phase: "AWAITING_INPUT" })), false)
	})

	it("returns true once the update carries a terminal outcome", () => {
		assert.equal(
			shouldClose(progress({ phase: "FINISHED", outcome: "COMPLETED" })),
			true,
		)
	})

	it("returns true for a FAILED terminal outcome too", () => {
		assert.equal(
			shouldClose(progress({ phase: "FINISHED", outcome: "FAILED" })),
			true,
		)
	})
})
