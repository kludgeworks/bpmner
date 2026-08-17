/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { JSDOM } from "jsdom"
import { applyUpdate, startRun } from "../src/run-model"
import type { RunUpdate } from "../src/run-update"
import {
	appendTelemetry,
	formatElapsed,
	paceText,
	renderPipeline,
	stepTime,
} from "../src/studio-dom"

function doc(): Document {
	const dom = new JSDOM("<!doctype html><body></body>")
	Object.assign(globalThis, { document: dom.window.document })
	return dom.window.document
}

const readinessDone: RunUpdate = {
	seq: 1,
	phase: "READINESS",
	artifactState: "NONE",
	summary: "Assessed input readiness (ready).",
	detail: { verdict: "READY" },
}

describe("studio dom", () => {
	it("formats elapsed time as m:ss", () => {
		assert.equal(formatElapsed(0), "0:00")
		assert.equal(formatElapsed(18_400), "0:18")
		assert.equal(formatElapsed(63_300), "1:03")
		assert.equal(formatElapsed(-500), "0:00", "a clock never runs backwards")
	})

	it("reports a spread for a stage whose observed runs disagree", () => {
		// Contract ran 12.5s and 30.9s across the two reference runs; a point estimate there
		// would be wrong by more than 2x.
		assert.match(
			paceText({ phase: "CONTRACT", state: "active", started: 0 }, 6_000),
			/0:06 elapsed · this stage varies — 12–31s observed/,
		)
		assert.match(
			paceText({ phase: "OUTLINE", state: "active", started: 0 }, 18_000),
			/0:18 elapsed · typically 25–26s/,
		)
	})

	it("shows live elapsed while active, the final duration once done, an estimate while pending", () => {
		assert.equal(
			stepTime({ phase: "LAYOUT", state: "active", started: 1_000 }, 4_000),
			"0:03",
		)
		assert.equal(
			stepTime(
				{ phase: "LAYOUT", state: "done", started: 1_000, ended: 3_500 },
				9_999,
			),
			"0:02",
		)
		assert.equal(
			stepTime(
				{ phase: "LAYOUT", state: "pending", started: 0, projected: true },
				0,
			),
			"~2s",
		)
	})

	it("renders three groups with the active one marked", () => {
		const document = doc()
		const container = document.createElement("ol")
		const state = applyUpdate(startRun(0), readinessDone, 12_400)

		renderPipeline(container, state, 20_000)

		const groups = [
			...container.querySelectorAll(".pipeline-group[data-group]"),
		]
		assert.deepEqual(
			groups.map((group) => (group as HTMLElement).dataset.group),
			["understand", "structure", "refine"],
		)
		assert.equal((groups[0] as HTMLElement).dataset.state, "active")

		// The row shown as active is the one now running, not the one just reported.
		const active = container.querySelector(
			'.pipeline-step[data-state="active"]',
		)
		assert.equal(
			active?.textContent?.includes("Identifying steps and decisions"),
			true,
		)
	})

	it("renders a row's detail values beneath it", () => {
		const document = doc()
		const container = document.createElement("ol")
		let state = applyUpdate(startRun(0), readinessDone, 12_400)
		state = applyUpdate(
			state,
			{
				seq: 2,
				phase: "CONTRACT",
				artifactState: "NONE",
				summary: "x",
				detail: { issueCount: "0" },
			},
			24_900,
		)

		renderPipeline(container, state, 30_000)

		const facts = container.querySelector(".pipeline-step-facts")
		assert.equal(facts?.textContent, "0 contract issues")
	})

	it("appends telemetry lines without rebuilding the log", () => {
		const document = doc()
		const log = document.createElement("ol")

		appendTelemetry(log, "client", 0, "POST /api/bpmn/generations → 202")
		appendTelemetry(
			log,
			"event",
			12_400,
			"seq 1 · READINESS · Assessed input readiness (ready).",
		)

		assert.equal(log.children.length, 2)
		assert.equal((log.children[0] as HTMLElement).dataset.kind, "client")
		assert.equal(log.children[1].querySelector("time")?.textContent, "0:12")
	})
})
