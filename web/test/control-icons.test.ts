/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import fs from "node:fs"
import path from "node:path"
import { describe, it } from "node:test"

/**
 * Regression guard for the blank canvas control icons (issue #698): the
 * zoom/export controls must render inline SVG, never a `bpmn-icon-*` class
 * targeting a codepoint the vendored bpmn-font does not define.
 *
 * Asserts against the shipped artifacts directly — the studio page and the
 * standalone preview template — rather than a hand-maintained test fixture, so
 * a regression is caught even when a fixture drifts out of sync.
 */

const CONTROL_IDS = [
	"zoom-reset-btn",
	"zoom-in-btn",
	"zoom-out-btn",
	"download-diagram-btn",
	"download-svg-btn",
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
	for (const id of CONTROL_IDS) {
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
