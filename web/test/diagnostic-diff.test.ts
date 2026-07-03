/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	type Diagnostic,
	initialDiff,
	keyOf,
	reduceDiagnostics,
} from "../src/diagnostic-diff"

const err = (over: Partial<Diagnostic> = {}): Diagnostic => ({
	source: "XSD",
	rule: "R1",
	elementId: "Task_1",
	message: "bad",
	severity: "ERROR",
	...over,
})

// ---------------------------------------------------------------------------
// keyOf — the ADR-ss-002 identity
// ---------------------------------------------------------------------------

describe("keyOf", () => {
	it("joins source|rule|elementId|message", () => {
		assert.equal(keyOf(err()), "XSD|R1|Task_1|bad")
	})

	it("renders undefined fields as empty segments", () => {
		assert.equal(
			keyOf({ message: "m" }),
			"||" + "|m", // source, rule, elementId empty; message last
		)
	})

	it("distinguishes diagnostics differing only by rule", () => {
		assert.notEqual(keyOf(err({ rule: "R1" })), keyOf(err({ rule: "R2" })))
	})
})

// ---------------------------------------------------------------------------
// reduceDiagnostics — dedup, fix-in-place, un-fix, attempt, severity
// ---------------------------------------------------------------------------

describe("reduceDiagnostics", () => {
	it("dedups identical keys within one snapshot to a single row", () => {
		const { state } = reduceDiagnostics(initialDiff(), {
			diagnostics: [err(), err(), err()],
		})
		assert.equal(state.rows.length, 1)
		assert.equal(state.rows[0].fixed, false)
	})

	it("marks a vanished diagnostic fixed, in place, without reordering", () => {
		const a = err({ elementId: "A", message: "a" })
		const b = err({ elementId: "B", message: "b" })
		const first = reduceDiagnostics(initialDiff(), { diagnostics: [a, b] })
		// Attempt 2: `a` is fixed (gone), `b` remains.
		const second = reduceDiagnostics(first.state, { diagnostics: [b] })
		assert.equal(second.state.rows.length, 2)
		// Order preserved: a at index 0 (struck), b at index 1 (live).
		assert.equal(keyOf(second.state.rows[0].diagnostic), keyOf(a))
		assert.equal(second.state.rows[0].fixed, true)
		assert.equal(keyOf(second.state.rows[1].diagnostic), keyOf(b))
		assert.equal(second.state.rows[1].fixed, false)
		// `a` transitioned to fixed this diff — overlay must be cleared.
		assert.deepEqual(second.newlyFixed, [keyOf(a)])
	})

	it("does not re-report an already-fixed key as newlyFixed", () => {
		const a = err({ elementId: "A", message: "a" })
		const b = err({ elementId: "B", message: "b" })
		const s1 = reduceDiagnostics(initialDiff(), { diagnostics: [a, b] })
		const s2 = reduceDiagnostics(s1.state, { diagnostics: [b] })
		assert.deepEqual(s2.newlyFixed, [keyOf(a)])
		// Third attempt, still gone — a stays fixed but is not "newly" fixed again.
		const s3 = reduceDiagnostics(s2.state, { diagnostics: [b] })
		assert.deepEqual(s3.newlyFixed, [])
		assert.equal(s3.state.rows[0].fixed, true)
	})

	it("un-fixes a re-appearing diagnostic and refreshes its payload", () => {
		const a = err({ elementId: "A", message: "a", severity: "ERROR" })
		const s1 = reduceDiagnostics(initialDiff(), { diagnostics: [a] })
		const s2 = reduceDiagnostics(s1.state, { diagnostics: [] })
		assert.equal(s2.state.rows[0].fixed, true)
		// It comes back (same key) with a downgraded severity.
		const back = err({ elementId: "A", message: "a", severity: "WARNING" })
		const s3 = reduceDiagnostics(s2.state, { diagnostics: [back] })
		assert.equal(s3.state.rows[0].fixed, false)
		assert.equal(s3.state.rows[0].diagnostic.severity, "WARNING")
		assert.deepEqual(s3.newlyFixed, [])
	})

	it("propagates attemptNumber, keeping the previous when omitted", () => {
		const s1 = reduceDiagnostics(initialDiff(), {
			diagnostics: [err()],
			attemptNumber: 2,
		})
		assert.equal(s1.state.attemptNumber, 2)
		const s2 = reduceDiagnostics(s1.state, { diagnostics: [err()] })
		assert.equal(s2.state.attemptNumber, 2)
	})

	it("carries severity and rule through onto the row", () => {
		const { state } = reduceDiagnostics(initialDiff(), {
			diagnostics: [err({ severity: "WARNING", rule: "R9" })],
		})
		assert.equal(state.rows[0].diagnostic.severity, "WARNING")
		assert.equal(state.rows[0].diagnostic.rule, "R9")
	})

	it("appends brand-new keys after existing rows in incoming order", () => {
		const a = err({ elementId: "A", message: "a" })
		const s1 = reduceDiagnostics(initialDiff(), { diagnostics: [a] })
		const b = err({ elementId: "B", message: "b" })
		const c = err({ elementId: "C", message: "c" })
		const s2 = reduceDiagnostics(s1.state, { diagnostics: [a, b, c] })
		assert.deepEqual(
			s2.state.rows.map((r) => r.diagnostic.elementId),
			["A", "B", "C"],
		)
		assert.ok(s2.state.rows.every((r) => !r.fixed))
	})
})

describe("initialDiff", () => {
	it("returns an empty, attempt-0 state", () => {
		const s = initialDiff()
		assert.deepEqual(s.rows, [])
		assert.equal(s.attemptNumber, 0)
	})

	it("returns a fresh object each call", () => {
		const a = initialDiff()
		a.rows.push({ diagnostic: err(), fixed: false })
		assert.equal(initialDiff().rows.length, 0)
	})
})
