/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { isTerminal, type RunUpdate, supersedes } from "../src/run-update"

function progress(overrides: Partial<RunUpdate> = {}): RunUpdate {
	return {
		seq: 1,
		phase: "READINESS",
		artifactState: "NONE",
		summary: "s",
		...overrides,
	}
}

describe("isTerminal", () => {
	it("is false for a Progress update (no outcome)", () => {
		assert.equal(isTerminal(progress()), false)
	})

	it("is true once outcome is present", () => {
		assert.equal(isTerminal(progress({ outcome: "COMPLETED" })), true)
	})
})

describe("supersedes — the stale-diagram guard", () => {
	it("is true against a null current (first update)", () => {
		assert.equal(supersedes(progress({ seq: 1 }), null), true)
	})

	it("a strictly higher seq supersedes a lower one", () => {
		const earlier = progress({ seq: 1 })
		const later = progress({ seq: 2 })
		assert.equal(supersedes(later, earlier), true)
	})

	it("an equal or lower seq never supersedes", () => {
		const a = progress({ seq: 5 })
		const b = progress({ seq: 5 })
		const earlier = progress({ seq: 4 })
		assert.equal(supersedes(a, b), false)
		assert.equal(supersedes(earlier, a), false)
	})
})
