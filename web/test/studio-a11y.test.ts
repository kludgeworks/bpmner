/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import axe from "axe-core"
import { JSDOM } from "jsdom"
import { renderClarifyForm } from "../src/clarify-form"
import { renderResultBar } from "../src/result-bar"
import { initialStages, renderStageRail } from "../src/stage-rail"

/**
 * Runtime accessibility smoke check (ADR-ss-006).
 *
 * Builds the studio DOM in jsdom, populates every dynamic region with real
 * component output, and runs axe-core over it. `color-contrast` is disabled:
 * jsdom has no CSS layout/paint engine, so contrast cannot be evaluated
 * (per ADR-ss-006 plan decision — the lighter jsdom+axe path, not Playwright).
 */

const STUDIO_BODY = `
  <div class="studio">
    <header class="studio-bar">
      <h1 class="studio-brand">bpmner</h1>
      <span id="version-footer" class="studio-version" aria-label="Application version">v0.3.2</span>
      <p class="studio-tagline">Generation Studio</p>
    </header>
    <aside class="studio-sidebar" aria-label="Controls and status">
      <section class="panel">
        <h2 class="panel-title">Describe</h2>
        <label class="visually-hidden" for="process-description">Process description</label>
        <textarea id="process-description" rows="8"></textarea>
        <button id="generate-btn" type="button" class="btn-primary">Generate</button>
      </section>
      <div id="clarify-region" class="hidden"></div>
      <section class="panel">
        <h2 class="panel-title">Pipeline</h2>
        <ol id="stage-rail" aria-label="Pipeline stages">
          <li data-stage="readiness" data-state="pending">Readiness</li>
          <li data-stage="contract" data-state="pending">Contract</li>
          <li data-stage="generate" data-state="pending">Generate</li>
          <li data-stage="validate" data-state="pending">Validate</li>
          <li data-stage="layout" data-state="pending">Layout</li>
          <li data-stage="align" data-state="pending">Align</li>
        </ol>
      </section>
      <div id="progress-container" class="panel">
        <h2 class="panel-title">Progress</h2>
        <ul id="progress-list"><li>Drafting…</li></ul>
      </div>
      <div id="result-bar"></div>
    </aside>
    <main class="studio-canvas" aria-label="Diagram canvas">
      <div id="canvas"></div>
      <div id="canvas-status" class="canvas-status"></div>
      <div class="io-zoom-controls">
        <ul class="io-zoom-reset io-control io-control-list" aria-label="Diagram zoom controls">
          <li><button id="zoom-reset-btn" type="button" title="Reset zoom" aria-label="Reset zoom"><span class="bpmn-icon-size-reset" aria-hidden="true"></span></button></li>
          <li><button id="zoom-in-btn" type="button" title="Zoom in" aria-label="Zoom in"><span class="bpmn-icon-plus" aria-hidden="true"></span></button></li>
          <li><button id="zoom-out-btn" type="button" title="Zoom out" aria-label="Zoom out"><span class="bpmn-icon-minus" aria-hidden="true"></span></button></li>
        </ul>
      </div>
      <div class="io-import-export">
        <ul class="io-export io-control io-control-list io-horizontal" aria-label="Diagram export actions">
          <li><a id="download-diagram-btn" role="button" aria-disabled="true" title="Download as BPMN 2.0 file"><span class="bpmn-icon-download" aria-hidden="true"></span></a></li>
          <li><a id="download-svg-btn" role="button" aria-disabled="true" title="Download as SVG image"><span class="bpmn-icon-picture" aria-hidden="true"></span></a></li>
        </ul>
      </div>
    </main>
  </div>
`

function required(doc: Document, id: string): HTMLElement {
	const el = doc.getElementById(id)
	if (!el) throw new Error(`fixture missing #${id}`)
	return el
}

describe("studio DOM accessibility (axe-core)", () => {
	it("has no axe violations with every region populated", async () => {
		const dom = new JSDOM(
			`<!DOCTYPE html><html lang="en"><head><title>bpmner</title></head><body>${STUDIO_BODY}</body></html>`,
		)
		const doc = dom.window.document

		// axe-core reads the global window/document at run time. (navigator is a
		// getter-only global in Node 24 and is left alone — axe reads it off the
		// jsdom window we set here.)
		Object.assign(globalThis, {
			window: dom.window,
			document: doc,
		})

		// Populate the dynamic regions with real component output.
		renderStageRail(required(doc, "stage-rail"), {
			...initialStages(),
			readiness: "done",
			contract: "active",
		})
		renderResultBar(required(doc, "result-bar"), {
			status: "VALIDATION_FAILED",
			diagnosticsSummary: "source=xsd: element is not well-formed",
			downloadUrl: "api/bpmn/generations/abc/bpmn",
		})
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
