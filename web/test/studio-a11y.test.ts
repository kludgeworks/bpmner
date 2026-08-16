/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { describe, it } from "node:test"
import axe from "axe-core"
import { JSDOM } from "jsdom"
import { renderClarifyForm } from "../src/clarify-form"
import { applyUpdate, startRun } from "../src/run-model"
import { renderPipeline } from "../src/studio-dom"

/**
 * Runtime accessibility smoke check (ADR-ss-006).
 *
 * Reads the **shipped** studio page, populates every dynamic region with real component
 * output, and runs axe-core over it. Reading the served artifact rather than a copy of it is
 * what keeps this check honest: a hand-maintained fixture passes while the real page regresses. `color-contrast` is disabled:
 * jsdom has no CSS layout/paint engine, so contrast cannot be evaluated
 * (per ADR-ss-006 plan decision — the lighter jsdom+axe path, not Playwright).
 */

// __dirname is the bundled test's directory (web/dist/test/), three levels below the
// repository root the shipped page is addressed from.
const STUDIO_HTML = fs.readFileSync(
	path.join(__dirname, "../../..", "web/src/static/index.html"),
	"utf8",
)

function required(doc: Document, id: string): HTMLElement {
	const el = doc.getElementById(id)
	if (!el) throw new Error(`fixture missing #${id}`)
	return el
}

describe("studio DOM accessibility (axe-core)", () => {
	it("has no axe violations with every region populated", async () => {
		const dom = new JSDOM(STUDIO_HTML)
		const doc = dom.window.document

		// axe-core reads the global window/document at run time. (navigator is a
		// getter-only global in Node 24 and is left alone — axe reads it off the
		// jsdom window we set here.)
		Object.assign(globalThis, {
			window: dom.window,
			document: doc,
		})

		// Populate the dynamic regions with real component output, and reveal every view: axe
		// skips hidden subtrees, so one view at a time would leave most of the studio unchecked.
		for (const view of doc.querySelectorAll(".view"))
			view.classList.remove("hidden")
		required(doc, "clarify-region").classList.remove("hidden")
		renderPipeline(
			required(doc, "pipeline"),
			applyUpdate(
				startRun(0),
				{
					seq: 1,
					phase: "READINESS",
					artifactState: "NONE",
					summary: "Assessed input readiness (ready).",
					detail: { verdict: "READY" },
				},
				12_400,
			),
			20_000,
		)
		renderClarifyForm(
			required(doc, "clarify-region"),
			{
				prompt: "What event starts the process?",
				options: ["Message received", "Timer fires"],
				round: 1,
				maxRounds: 3,
				submitting: false,
			},
			() => undefined,
		)

		const results = await axe.run(doc.body, {
			rules: { "color-contrast": { enabled: false } },
		})

		const summary = results.violations.map((v) => ({
			id: v.id,
			nodes: v.nodes.length,
		}))
		assert.deepEqual(
			results.violations.map((v) => v.id),
			[],
			`axe violations: ${JSON.stringify(summary, null, 2)}`,
		)
	})
})
