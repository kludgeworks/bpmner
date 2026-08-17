/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { describe, it } from "node:test"

/**
 * The canvas zoom and export controls render their icons as inline SVG. A `bpmn-icon-*` class
 * is not a valid source for them: the vendored bpmn-font defines no codepoint for these glyphs,
 * so such a class renders a blank control.
 *
 * Asserts against the shipped artifacts — the studio page and the standalone preview template —
 * rather than a hand-maintained fixture, so the assertion cannot drift from what is served.
 */

/** Icon-only controls: with no text, a failed glyph leaves a blank button. */
const ICON_CONTROL_IDS = ["zoom-reset-btn", "zoom-in-btn", "zoom-out-btn"]

/** The studio's export controls carry their own text, so they need no icon at all. */
const LABELLED_CONTROLS: Array<[string, string]> = [
	["download-diagram-btn", "BPMN 2.0 XML"],
	["download-svg-btn", "SVG"],
]

/** Glyph names with no counterpart in the vendored bpmn-font. */
const PHANTOM_GLYPHS = /bpmn-icon-(size-reset|plus|minus|download|picture)\b/

// __dirname is the bundled test's directory (web/dist/test/), three levels
// below the repository root that both artifacts are addressed from.
const REPO_ROOT = path.join(__dirname, "../../..")

function readArtifact(relativePath: string): string {
	return fs.readFileSync(path.join(REPO_ROOT, relativePath), "utf8")
}

function assertControlsUseInlineSvg(html: string, label: string): void {
	assert.doesNotMatch(
		html,
		PHANTOM_GLYPHS,
		`${label} still references a canvas-control glyph class`,
	)
	for (const id of ICON_CONTROL_IDS) {
		assert.match(
			html,
			new RegExp(`id="${id}"[\\s\\S]{0,400}?<svg`),
			`${label} control #${id} has no inline <svg> icon`,
		)
	}
}

describe("canvas control icons render as inline SVG", () => {
	it("studio page", () => {
		assertControlsUseInlineSvg(
			readArtifact("web/src/static/index.html"),
			"web/src/static/index.html",
		)
	})

	it("studio export controls are labelled with text", () => {
		const html = readArtifact("web/src/static/index.html")
		for (const [id, label] of LABELLED_CONTROLS) {
			assert.match(
				html,
				new RegExp(
					`id="${id}"[\\s\\S]{0,400}?${label.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}`,
				),
				`export control #${id} has no visible label`,
			)
		}
	})

	it("standalone preview template", () => {
		assertControlsUseInlineSvg(
			readArtifact("src/main/resources/preview/preview-template.html"),
			"src/main/resources/preview/preview-template.html",
		)
	})

	it("leaves the vendored bpmn-font stylesheet free of control glyphs", () => {
		assert.doesNotMatch(
			readArtifact("web/src/static/bpmn-font/css/bpmn.css"),
			PHANTOM_GLYPHS,
			"bpmn-font/css/bpmn.css should not define canvas-control glyph classes",
		)
	})
})

describe("bpmn.io watermark is never repositioned by our styling", () => {
	// The bpmn-js license requires the watermark stay visible and unoverlapped;
	// our controls move to clear it, it does not move to clear our controls.
	it("studio stylesheet", () => {
		assert.doesNotMatch(
			readArtifact("web/src/static/style.css"),
			/bjs-powered-by/,
			"style.css must not override the bpmn.io watermark's position",
		)
	})

	it("preview template", () => {
		assert.doesNotMatch(
			readArtifact("src/main/resources/preview/preview-template.html"),
			/\.bjs-powered-by\s*\{/,
			"preview template must not override the bpmn.io watermark's position",
		)
	})
})
