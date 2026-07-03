/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	buildResultBarHtml,
	type ResultBarState,
	type ResultStatus,
	renderResultBar,
} from "../src/result-bar"

// ---------------------------------------------------------------------------
// buildResultBarHtml — pure function, no DOM needed
// ---------------------------------------------------------------------------

describe("buildResultBarHtml — returns empty string when no status", () => {
	it("returns empty string for empty state", () => {
		assert.equal(buildResultBarHtml({}), "")
	})
})

describe("buildResultBarHtml — each status renders a distinct badge", () => {
	const statuses: ResultStatus[] = [
		"GENERATED",
		"NEEDS_CLARIFICATION",
		"ALIGNMENT_FAILED",
		"VALIDATION_FAILED",
	]

	for (const status of statuses) {
		it(`renders badge for ${status}`, () => {
			const html = buildResultBarHtml({ status })
			assert.ok(
				html.includes(`data-result-status="${status}"`),
				`expected data-result-status="${status}" in: ${html}`,
			)
		})
	}

	it("each status renders a different label text", () => {
		const labels = statuses.map(
			(s) =>
				buildResultBarHtml({ status: s }).match(
					/<span[^>]*>([^<]+)<\/span>/,
				)?.[1],
		)
		const unique = new Set(labels)
		assert.equal(
			unique.size,
			statuses.length,
			"each status must have a unique label",
		)
	})
})

describe("buildResultBarHtml — download anchor", () => {
	it("includes download anchor for GENERATED with downloadUrl", () => {
		const html = buildResultBarHtml({
			status: "GENERATED",
			downloadUrl: "api/bpmn/generations/proc-1/bpmn",
		})
		assert.ok(
			html.includes('class="download-bpmn"'),
			"should have download-bpmn class",
		)
		assert.ok(
			html.includes("api/bpmn/generations/proc-1/bpmn"),
			"should include the processId in href",
		)
		assert.ok(html.includes(" download"), "should have download attribute")
	})

	it("omits download anchor for ALIGNMENT_FAILED (no XML artifact)", () => {
		const html = buildResultBarHtml({
			status: "ALIGNMENT_FAILED",
			downloadUrl: "api/bpmn/generations/proc-1/bpmn",
		})
		assert.ok(
			!html.includes("download-bpmn"),
			"ALIGNMENT_FAILED should not render download",
		)
	})

	it("omits download anchor when downloadUrl is absent", () => {
		const html = buildResultBarHtml({ status: "GENERATED" })
		assert.ok(!html.includes("download-bpmn"), "no download anchor without url")
	})
})

describe("buildResultBarHtml — alignment report", () => {
	it("renders <details> with verdict and report when both are present", () => {
		const html = buildResultBarHtml({
			status: "ALIGNMENT_FAILED",
			alignmentVerdict: "FAILED",
			alignmentReport: "Contract item missing",
		})
		assert.ok(html.includes("<details"), "should include <details>")
		assert.ok(html.includes("FAILED"), "should include verdict in summary")
		assert.ok(
			html.includes("Contract item missing"),
			"should include rationale",
		)
	})

	it("omits <details> when alignmentReport is absent", () => {
		const html = buildResultBarHtml({ status: "GENERATED" })
		assert.ok(
			!html.includes("<details"),
			"no <details> without alignmentReport",
		)
	})

	it("HTML-escapes report content", () => {
		const html = buildResultBarHtml({
			status: "ALIGNMENT_FAILED",
			alignmentReport: '<script>alert("xss")</script>',
		})
		assert.ok(
			html.includes("&lt;script&gt;"),
			"angle brackets should be escaped",
		)
		assert.ok(!html.includes("<script>"), "raw script tag must not appear")
	})
})

describe("buildResultBarHtml — cost summary", () => {
	it("renders cost pre when costSummary is set", () => {
		const html = buildResultBarHtml({
			status: "GENERATED",
			costSummary: "Total: $0.12 | 1234 tokens",
		})
		assert.ok(
			html.includes('class="run-cost"'),
			"should include run-cost class",
		)
		assert.ok(html.includes("Total: $0.12"), "should include cost text")
	})

	it("omits cost pre when costSummary is absent", () => {
		const html = buildResultBarHtml({ status: "GENERATED" })
		assert.ok(!html.includes("run-cost"), "no cost element when summary absent")
	})
})

// ---------------------------------------------------------------------------
// renderResultBar — minimal DOM mock (no jsdom needed)
// ---------------------------------------------------------------------------

function makeContainer() {
	const classes = new Set<string>(["hidden"])
	const el: {
		innerHTML: string
		classList: {
			add: (c: string) => void
			remove: (c: string) => void
			has: (c: string) => boolean
		}
	} = {
		innerHTML: "",
		classList: {
			add: (c: string) => classes.add(c),
			remove: (c: string) => classes.delete(c),
			has: (c: string) => classes.has(c),
		},
	}
	return { el: el as unknown as HTMLElement, classes }
}

describe("renderResultBar — visibility", () => {
	it("keeps container hidden and clears innerHTML when state has no status", () => {
		const { el, classes } = makeContainer()
		el.innerHTML = "old content"
		renderResultBar(el, {})

		assert.ok(classes.has("hidden"), "should remain hidden")
		assert.equal(el.innerHTML, "", "innerHTML should be cleared")
	})

	it("removes hidden class and sets innerHTML when status is present", () => {
		const { el, classes } = makeContainer()
		renderResultBar(el, { status: "GENERATED" })

		assert.ok(!classes.has("hidden"), "should not be hidden")
		assert.ok(el.innerHTML.length > 0, "innerHTML should be non-empty")
	})
})

describe("renderResultBar — full state round-trip", () => {
	it("renders GENERATED result with download url and cost", () => {
		const { el } = makeContainer()
		const state: ResultBarState = {
			status: "GENERATED",
			downloadUrl: "api/bpmn/generations/abc/bpmn",
			costSummary: "$0.05",
		}
		renderResultBar(el, state)

		assert.ok(el.innerHTML.includes("GENERATED"), "status in output")
		assert.ok(el.innerHTML.includes("abc/bpmn"), "download url in output")
		assert.ok(el.innerHTML.includes("$0.05"), "cost in output")
	})
})
