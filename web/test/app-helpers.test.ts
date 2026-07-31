/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	buildClarifyState,
	buildResultBarState,
	parseRunUpdate,
} from "../src/app-helpers"
import type { RunUpdate } from "../src/run-update"

function progress(overrides: Partial<RunUpdate> = {}): RunUpdate {
	return {
		seq: 1,
		phase: "READINESS",
		artifactState: "NONE",
		summary: "s",
		...overrides,
	}
}

describe("parseRunUpdate", () => {
	it("parses a well-formed RunUpdate payload", () => {
		const update = parseRunUpdate(
			'{"seq":1,"phase":"DRAFT","artifactState":"XML_DRAFT","summary":"Rendered a draft."}',
		)
		assert.deepEqual(update, {
			seq: 1,
			phase: "DRAFT",
			artifactState: "XML_DRAFT",
			summary: "Rendered a draft.",
		})
	})

	it("returns null for malformed JSON instead of throwing", () => {
		assert.equal(parseRunUpdate("not json"), null)
	})
})

describe("buildClarifyState", () => {
	it("builds prompt/round/maxRounds from summary and detail", () => {
		const update = progress({
			phase: "AWAITING_INPUT",
			summary: "What event starts the process?",
			detail: { round: "2", maxRounds: "3" },
		})

		assert.deepEqual(buildClarifyState(update), {
			prompt: "What event starts the process?",
			options: [],
			round: 2,
			maxRounds: 3,
			submitting: false,
		})
	})

	it("splits pipe-joined options", () => {
		const update = progress({
			phase: "AWAITING_INPUT",
			detail: { options: "Message received|Timer fires" },
		})

		assert.deepEqual(buildClarifyState(update).options, [
			"Message received",
			"Timer fires",
		])
	})

	it("defaults round/maxRounds to 1 when detail omits them", () => {
		const update = progress({ phase: "AWAITING_INPUT", detail: {} })

		const state = buildClarifyState(update)
		assert.equal(state.round, 1)
		assert.equal(state.maxRounds, 1)
	})
})

describe("buildResultBarState", () => {
	it("carries status/alignment/diagnostics through from detail", () => {
		const update = progress({
			artifactState: "FINAL",
			outcome: "COMPLETED",
			detail: {
				status: "GENERATED",
				alignmentVerdict: "ALIGNED",
				alignmentReport: "Matches the process contract.",
			},
		})

		const state = buildResultBarState(update, "proc-1")
		assert.equal(state.status, "GENERATED")
		assert.equal(state.alignmentVerdict, "ALIGNED")
		assert.equal(state.alignmentReport, "Matches the process contract.")
	})

	it("sets downloadUrl only when an artifact exists and a processId is known", () => {
		const withArtifact = progress({
			artifactState: "FINAL",
			outcome: "COMPLETED",
		})
		assert.equal(
			buildResultBarState(withArtifact, "proc-1").downloadUrl,
			"api/bpmn/generations/proc-1/bpmn",
		)

		const noArtifact = progress({ artifactState: "NONE", outcome: "FAILED" })
		assert.equal(
			buildResultBarState(noArtifact, "proc-1").downloadUrl,
			undefined,
		)

		assert.equal(buildResultBarState(withArtifact, null).downloadUrl, undefined)
	})

	it("surfaces diagnostics only when present in detail", () => {
		const failed = progress({
			artifactState: "DIAGNOSTIC",
			outcome: "FAILED",
			detail: {
				status: "VALIDATION_FAILED",
				diagnostics: "source=xsd: bad element",
			},
		})

		assert.equal(
			buildResultBarState(failed, "proc-1").diagnosticsSummary,
			"source=xsd: bad element",
		)
	})
})
