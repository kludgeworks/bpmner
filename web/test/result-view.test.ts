/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { JSDOM } from "jsdom"
import {
	alignmentOf,
	buildMetrics,
	buildTimings,
	diagnosticsOf,
	renderSummary,
} from "../src/result-view"
import {
	applyUpdate,
	initialRunState,
	type RunState,
	startRun,
} from "../src/run-model"
import type { RunUpdate } from "../src/run-update"

function doc(): Document {
	const dom = new JSDOM("<!doctype html><body></body>")
	Object.assign(globalThis, { document: dom.window.document })
	return dom.window.document
}

let seq = 0
function at(
	phase: RunUpdate["phase"],
	artifactState: RunUpdate["artifactState"],
	detail?: Record<string, string>,
) {
	seq += 1
	return { seq, phase, artifactState, summary: `${phase}`, detail }
}

/** A run that reaches layout, with one repair attempt on the way. */
function runWithRepair(): RunState {
	seq = 0
	let state = startRun(0)
	state = applyUpdate(
		state,
		at("READINESS", "NONE", { verdict: "READY" }),
		12_000,
	)
	state = applyUpdate(
		state,
		at("CONTRACT", "NONE", { issueCount: "2" }),
		30_000,
	)
	state = applyUpdate(
		state,
		at("OUTLINE", "GRAPH_DRAFT", {
			nodeCount: "24",
			edgeCount: "27",
			conformanceCorrections: "5",
		}),
		55_000,
	)
	state = applyUpdate(state, at("DRAFT", "XML_DRAFT"), 56_000)
	state = applyUpdate(
		state,
		at("VALIDATION", "DIAGNOSTIC", { attemptNumber: "1" }),
		64_000,
	)
	state = applyUpdate(state, at("VALIDATION", "XML_DRAFT"), 72_000)
	return state
}

describe("result view", () => {
	it("derives the headline counts from the details the run already sent", () => {
		assert.deepEqual(buildMetrics(runWithRepair()), [
			{ label: "Activities", value: "24" },
			{ label: "Connections", value: "27" },
			{ label: "Contract issues", value: "2" },
			{ label: "Auto-corrections", value: "5" },
			{ label: "Repair attempts", value: "1" },
		])
	})

	it("counts repair attempts from the diagnostic updates seen", () => {
		// attemptNumber rides only a failing validation, so a passing run reports no total and
		// the count has to come from the occurrences themselves.
		seq = 0
		let clean = startRun(0)
		clean = applyUpdate(
			clean,
			at("READINESS", "NONE", { verdict: "READY" }),
			12_000,
		)
		clean = applyUpdate(
			clean,
			at("CONTRACT", "NONE", { issueCount: "0" }),
			24_000,
		)

		assert.equal(
			buildMetrics(clean).some((metric) => metric.label === "Repair attempts"),
			false,
		)
	})

	it("counts every repair round, not just the last diagnostic occurrence", () => {
		// The VALIDATION row is updated in place across repeats (ADDENDUM-1), so a filter over
		// occurrences can never see more than one row — the count must come from the highest
		// attemptNumber the run reported, not from how many rows carry one.
		seq = 0
		let state = startRun(0)
		state = applyUpdate(
			state,
			at("READINESS", "NONE", { verdict: "READY" }),
			12_000,
		)
		state = applyUpdate(
			state,
			at("CONTRACT", "NONE", { issueCount: "0" }),
			24_000,
		)
		state = applyUpdate(state, at("OUTLINE", "GRAPH_DRAFT"), 40_000)
		state = applyUpdate(state, at("DRAFT", "XML_DRAFT"), 41_000)
		state = applyUpdate(
			state,
			at("VALIDATION", "DIAGNOSTIC", { attemptNumber: "1" }),
			48_000,
		)
		state = applyUpdate(
			state,
			at("VALIDATION", "DIAGNOSTIC", { attemptNumber: "2" }),
			55_000,
		)
		state = applyUpdate(state, at("VALIDATION", "XML_DRAFT"), 60_000)

		assert.deepEqual(
			buildMetrics(state).find((metric) => metric.label === "Repair attempts"),
			{ label: "Repair attempts", value: "2" },
		)
	})

	it("times each stage from the gap between updates, scaled against the longest", () => {
		const timings = buildTimings(runWithRepair())

		// The merged OUTLINE+DRAFT row runs from the contract update to the draft update, so it
		// spans 30s to 56s and is the longest stage in this run.
		assert.deepEqual(timings[0], {
			label: "Read",
			ms: 12_000,
			share: 12 / 26,
		})
		const outline = timings.find((timing) => timing.label === "Flow")
		assert.equal(outline?.ms, 26_000)
		assert.equal(outline?.share, 1, "the longest stage fills the track")
	})

	it("omits an unfinished stage from the timings", () => {
		const timings = buildTimings(startRun(0))
		assert.deepEqual(timings, [])
	})

	it("reads the alignment verdict and rationale off the terminal update", () => {
		assert.deepEqual(
			alignmentOf({
				seq: 9,
				phase: "FINISHED",
				artifactState: "FINAL",
				summary: "done",
				outcome: "COMPLETED",
				detail: {
					alignmentVerdict: "PARTIALLY_ALIGNED",
					alignmentReport: "Two tasks differ.",
				},
			}),
			{ verdict: "partially aligned", rationale: "Two tasks differ." },
		)
		assert.equal(alignmentOf(null), null)
	})

	it("surfaces diagnostics only when the run reports them", () => {
		assert.equal(diagnosticsOf(null), null)
		assert.equal(
			diagnosticsOf({
				seq: 9,
				phase: "FINISHED",
				artifactState: "DIAGNOSTIC",
				summary: "failed",
				outcome: "FAILED",
				detail: { diagnostics: "source=xsd: not well-formed" },
			}),
			"source=xsd: not well-formed",
		)
	})

	it("renders only the sections that have something to say", () => {
		const document = doc()
		const container = document.createElement("div")

		renderSummary(container, runWithRepair())
		assert.deepEqual(
			[...container.querySelectorAll(".summary-title")].map(
				(el) => el.textContent,
			),
			["What was built", "Where the time went"],
		)

		renderSummary(container, initialRunState())
		assert.equal(
			container.children.length,
			0,
			"an empty run renders no empty sections",
		)
	})

	it("renders alignment and diagnostics when the terminal carries them", () => {
		const document = doc()
		const container = document.createElement("div")
		let state = runWithRepair()
		state = applyUpdate(
			state,
			{
				seq: 99,
				phase: "FINISHED",
				artifactState: "DIAGNOSTIC",
				summary: "Validation failed — generation stopped.",
				outcome: "FAILED",
				detail: {
					status: "VALIDATION_FAILED",
					alignmentVerdict: "ALIGNED",
					alignmentReport: "Matches.",
					diagnostics: "source=lint: label too long",
				},
			},
			80_000,
		)

		renderSummary(container, state)
		assert.deepEqual(
			[...container.querySelectorAll(".summary-title")].map(
				(el) => el.textContent,
			),
			["What was built", "Where the time went", "Alignment", "Diagnostics"],
		)
		assert.equal(
			container.querySelector(".summary-diagnostics")?.textContent,
			"source=lint: label too long",
		)
	})
})
