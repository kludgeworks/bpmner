/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	answerSubmitted,
	applyUpdate,
	displayRows,
	groupRows,
	initialRunState,
	type RunState,
	rowFacts,
	rowLabel,
	startRun,
} from "../src/run-model"
import type { ArtifactState, RunPhase, RunUpdate } from "../src/run-update"

let seq = 0

function progress(
	phase: RunPhase,
	artifactState: ArtifactState = "NONE",
	detail?: Record<string, string>,
): RunUpdate {
	seq += 1
	return { seq, phase, artifactState, summary: `${phase} done`, detail }
}

function terminal(): RunUpdate {
	seq += 1
	return {
		seq,
		phase: "FINISHED",
		artifactState: "FINAL",
		summary: "BPMN generation complete.",
		outcome: "COMPLETED",
		detail: { status: "GENERATED" },
	}
}

function run(state: RunState, ...updates: RunUpdate[]): RunState {
	return updates.reduce(
		(acc, update) => applyUpdate(acc, update, update.seq * 1000),
		state,
	)
}

function active(state: RunState): RunPhase | undefined {
	return state.occurrences.find(
		(row) => row.state === "active" || row.state === "repeat",
	)?.phase
}

describe("run model", () => {
	it("opens with readiness running before any update arrives", () => {
		// The first update lands 12-15s after submit; until then the run must still name what it
		// is doing rather than showing nothing.
		seq = 0
		const state = startRun(0)

		assert.equal(active(state), "READINESS")
		assert.equal(state.occurrences[0].started, 0)
	})

	it("treats an update as a completion and opens the phase that is now running", () => {
		seq = 0
		const state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "READY" }),
		)

		const [readiness, contract] = state.occurrences
		assert.equal(readiness.phase, "READINESS")
		assert.equal(readiness.state, "done")
		assert.equal(contract.phase, "CONTRACT")
		assert.equal(contract.state, "active")
	})

	it("holds one row open across OUTLINE and DRAFT", () => {
		// DRAFT lands 0.3-0.8s after OUTLINE; a row that flashed for that long would be noise.
		seq = 0
		const state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "READY" }),
			progress("CONTRACT"),
			progress("OUTLINE", "GRAPH_DRAFT", { nodeCount: "17", edgeCount: "18" }),
		)

		const outline = state.occurrences.filter((row) => row.phase === "OUTLINE")
		assert.equal(outline.length, 1)
		assert.equal(
			outline[0].state,
			"active",
			"OUTLINE stays open until DRAFT closes it",
		)
		assert.equal(outline[0].detail?.nodeCount, "17")

		const next = run(state, progress("DRAFT", "XML_DRAFT"))
		assert.equal(
			next.occurrences.find((row) => row.phase === "OUTLINE")?.state,
			"done",
		)
		assert.equal(active(next), "VALIDATION")
	})

	it("repeats validation on a repair attempt instead of advancing", () => {
		seq = 0
		const state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "READY" }),
			progress("CONTRACT"),
			progress("OUTLINE", "GRAPH_DRAFT"),
			progress("DRAFT", "XML_DRAFT"),
			progress("VALIDATION", "DIAGNOSTIC", {
				attemptNumber: "1",
				lintIssues: "3",
			}),
		)

		assert.equal(active(state), "VALIDATION")
		const validation = state.occurrences.filter(
			(row) => row.phase === "VALIDATION",
		)
		assert.equal(
			validation.length,
			1,
			"a repair attempt must not stack a second row",
		)
		assert.equal(validation[0].state, "repeat")
		assert.equal(validation[0].attempt, 1)

		const passed = run(state, progress("VALIDATION", "XML_DRAFT"))
		assert.equal(active(passed), "LAYOUT")
	})

	it("expects a question when the readiness verdict is not READY", () => {
		seq = 0
		const state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "NEEDS_CLARIFICATION" }),
		)

		const current = state.occurrences[state.occurrences.length - 1]
		assert.equal(current.phase, "AWAITING_INPUT")
		assert.equal(
			current.anticipating,
			true,
			"the question is announced before it arrives",
		)
		assert.equal(
			state.paused,
			false,
			"the run only pauses once the question itself lands",
		)
	})

	it("falls back to the summary when detail.verdict is absent", () => {
		// An older server emits the verdict only in the prose; taking the ready branch there would
		// send the run down the wrong path.
		seq = 0
		const update = progress("READINESS")
		update.summary = "Assessed input readiness (needs_clarification)."
		const state = run(startRun(0), update)

		assert.equal(
			state.occurrences[state.occurrences.length - 1].phase,
			"AWAITING_INPUT",
		)
	})

	it("re-reads after an answer rather than jumping to contract", () => {
		// Answering runs reassess, which publishes its own readiness assessment, so the next
		// update is another READINESS.
		seq = 0
		let state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "NEEDS_CLARIFICATION" }),
			progress("AWAITING_INPUT", "NONE", { round: "1", maxRounds: "3" }),
		)
		assert.equal(state.paused, true)

		state = answerSubmitted(state, 20_000)
		assert.equal(state.paused, false)
		const current = state.occurrences[state.occurrences.length - 1]
		assert.equal(current.phase, "READINESS")
		assert.equal(
			current.again,
			true,
			"a re-read is distinguishable from the opening read",
		)

		state = run(state, progress("READINESS", "NONE", { verdict: "READY" }))
		assert.equal(active(state), "CONTRACT")
	})

	it("gives every clarification round its own row", () => {
		seq = 0
		let state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "NEEDS_CLARIFICATION" }),
			progress("AWAITING_INPUT", "NONE", { round: "1", maxRounds: "3" }),
		)
		state = answerSubmitted(state, 20_000)
		state = run(
			state,
			progress("READINESS", "NONE", { verdict: "NEEDS_CLARIFICATION" }),
			progress("AWAITING_INPUT", "NONE", { round: "2", maxRounds: "3" }),
		)
		state = answerSubmitted(state, 40_000)
		state = run(state, progress("READINESS", "NONE", { verdict: "READY" }))

		assert.deepEqual(
			state.occurrences.map((row) => row.phase),
			[
				"READINESS",
				"AWAITING_INPUT",
				"READINESS",
				"AWAITING_INPUT",
				"READINESS",
				"CONTRACT",
			],
		)
		assert.deepEqual(
			state.occurrences
				.filter((row) => row.phase === "AWAITING_INPUT")
				.map((row) => row.round),
			[1, 2],
		)
	})

	it("drops replayed updates and counts them", () => {
		// A reconnect re-delivers from seq 1; state must not advance twice on the same update.
		seq = 0
		const first = progress("READINESS", "NONE", { verdict: "READY" })
		const state = run(startRun(0), first, first, first)

		assert.equal(state.ignored, 2)
		assert.equal(
			state.occurrences.length,
			2,
			"a replay must not re-open a phase",
		)
		assert.equal(state.lastSeq, first.seq)
	})

	it("closes every occurrence on the terminal update", () => {
		seq = 0
		const state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "READY" }),
			terminal(),
		)

		assert.ok(state.terminal)
		assert.ok(state.occurrences.every((row) => row.state === "done"))
	})

	it("projects the phases still to come, and stops projecting once terminal", () => {
		seq = 0
		const state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "READY" }),
		)

		const rows = displayRows(state)
		assert.equal(rows.filter((row) => !row.projected).length, 2)
		assert.deepEqual(
			rows.filter((row) => row.projected).map((row) => row.phase),
			["OUTLINE", "VALIDATION", "LAYOUT", "ALIGNMENT", "FINISHED"],
		)
		assert.ok(
			rows
				.filter((row) => row.projected)
				.every((row) => row.state === "pending"),
		)

		const done = run(state, terminal())
		assert.ok(displayRows(done).every((row) => !row.projected))
	})

	it("starts empty", () => {
		const state = initialRunState()
		assert.deepEqual(state.occurrences, [])
		assert.equal(state.terminal, null)
	})
	it("labels a row by the work in flight, distinguishing a re-read and a repair", () => {
		assert.equal(
			rowLabel({ phase: "READINESS", state: "active", started: 0 }),
			"Reading your description",
		)
		assert.equal(
			rowLabel({
				phase: "READINESS",
				state: "active",
				started: 0,
				again: true,
			}),
			"Re-reading with your answer",
		)
		assert.equal(
			rowLabel({
				phase: "AWAITING_INPUT",
				state: "active",
				started: 0,
				anticipating: true,
			}),
			"Preparing a question",
		)
		assert.equal(
			rowLabel({
				phase: "VALIDATION",
				state: "repeat",
				started: 0,
				attempt: 2,
			}),
			"Repairing the diagram (attempt 2)",
		)
	})

	it("words the detail values a row carries", () => {
		assert.deepEqual(
			rowFacts({
				phase: "OUTLINE",
				state: "done",
				started: 0,
				detail: {
					nodeCount: "17",
					edgeCount: "18",
					conformanceCorrections: "2",
				},
			}),
			["17 activities", "18 connections", "2 auto-corrections"],
		)
		assert.deepEqual(
			rowFacts({ phase: "LAYOUT", state: "done", started: 0 }),
			[],
		)
	})

	it("groups rows into contiguous runs and keeps FINISHED as an ungrouped tail", () => {
		seq = 0
		const state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "READY" }),
		)
		const { groups, tail } = groupRows(displayRows(state))

		assert.deepEqual(
			groups.map((group) => group.key),
			["understand", "structure", "refine"],
		)
		assert.equal(groups[0].title, "Understand")
		assert.equal(
			groups[0].state,
			"active",
			"the group holding the active row is active",
		)
		assert.equal(groups[1].state, "pending")
		assert.deepEqual(
			tail.map((row) => row.phase),
			["FINISHED"],
		)
	})

	it("keeps a repeated phase inside its own group", () => {
		seq = 0
		let state = run(
			startRun(0),
			progress("READINESS", "NONE", { verdict: "NEEDS_CLARIFICATION" }),
			progress("AWAITING_INPUT", "NONE", { round: "1", maxRounds: "3" }),
		)
		state = answerSubmitted(state, 20_000)
		const { groups } = groupRows(displayRows(state))

		// Readiness, the question and the re-read all belong to Understand, in arrival order,
		// followed by the contract row still projected ahead of them.
		assert.deepEqual(
			groups[0].rows.map((row) => row.phase),
			["READINESS", "AWAITING_INPUT", "READINESS", "CONTRACT"],
		)
		assert.deepEqual(
			groups[0].rows.map((row) => row.projected === true),
			[false, false, false, true],
		)
	})
})
