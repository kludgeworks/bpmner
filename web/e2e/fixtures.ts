/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import fs from "node:fs"
import path from "node:path"
import type { Page } from "@playwright/test"

/**
 * Canned responses shaped exactly as the server sends them, so these specs exercise the client
 * without a backend and without a model call. Values are taken from a real logged run.
 */

export const PROCESS_ID = "quizzical_brahmagupta"

type Update = Record<string, unknown>

/** One SSE frame per update; the browser parses `data:` lines and nothing else. */
export function sse(updates: Update[]): string {
	return `${updates.map((update) => `data: ${JSON.stringify(update)}`).join("\n\n")}\n\n`
}

/** Readiness through to the terminal, as a run with nothing to clarify reports it. */
export const HAPPY_PATH: Update[] = [
	{
		seq: 1,
		phase: "READINESS",
		artifactState: "NONE",
		summary: "Assessed input readiness (ready).",
		detail: { verdict: "READY" },
	},
	{
		seq: 2,
		phase: "CONTRACT",
		artifactState: "NONE",
		summary: "Extracted the process contract.",
		detail: { issueCount: "0" },
	},
	{
		seq: 3,
		phase: "OUTLINE",
		artifactState: "GRAPH_DRAFT",
		summary: "Composed the process graph structure.",
		detail: { nodeCount: "17", edgeCount: "18", conformanceCorrections: "2" },
	},
	{
		seq: 4,
		phase: "DRAFT",
		artifactState: "XML_DRAFT",
		summary: "Rendered a draft BPMN diagram.",
	},
	{
		seq: 5,
		phase: "VALIDATION",
		artifactState: "XML_DRAFT",
		summary: "Validation passed after 0 repair attempt(s).",
	},
	{
		seq: 6,
		phase: "LAYOUT",
		artifactState: "XML_DRAFT",
		summary: "Applied automatic diagram layout.",
	},
	{
		seq: 7,
		phase: "ALIGNMENT",
		artifactState: "XML_DRAFT",
		summary: "Checked semantic alignment (aligned).",
	},
	{
		seq: 8,
		phase: "FINISHED",
		artifactState: "FINAL",
		summary: "BPMN generation complete.",
		outcome: "COMPLETED",
		detail: {
			status: "GENERATED",
			alignmentVerdict: "ALIGNED",
			alignmentReport: "Matches.",
		},
	},
]

/**
 * A run that parks on the user. The stream stops after the question, exactly as the server does:
 * nothing further arrives until an answer is posted.
 */
export const AWAITING_ANSWER: Update[] = [
	{
		seq: 1,
		phase: "READINESS",
		artifactState: "NONE",
		summary: "Assessed input readiness (needs_clarification).",
		detail: { verdict: "NEEDS_CLARIFICATION" },
	},
	{
		seq: 2,
		phase: "AWAITING_INPUT",
		artifactState: "NONE",
		summary: "What final state should the process reach?",
		detail: {
			round: "1",
			maxRounds: "3",
			options: "The order is completed|The order remains open",
		},
	},
]

/**
 * A run with no `detail.status` at all — the shape of the four non-status terminals (aborted,
 * platform failure, stuck, no result), none of which is one of the seven `BpmnGenerationStatus`
 * values. `artifactState: "NONE"` still drives the empty state, but the badge and headline must
 * not read as a generated diagram.
 */
export const ABORTED_NO_STATUS: Update[] = [
	{
		seq: 1,
		phase: "FINISHED",
		artifactState: "NONE",
		summary: "BPMN generation stopped unexpectedly.",
		outcome: "FAILED",
	},
]

const MINIMAL_BPMN = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" id="Definitions_1"
  targetNamespace="http://bpmn.io/schema/bpmn">
  <bpmn:process id="Process_1" isExecutable="false">
    <bpmn:startEvent id="StartEvent_1" name="Start" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_1">
      <bpmndi:BPMNShape id="StartEvent_1_di" bpmnElement="StartEvent_1">
        <dc:Bounds x="150" y="100" width="36" height="36" />
      </bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`

/** The minimal diagram with its start event renamed, so two runs' diagrams are distinguishable
 * on screen without inspecting bpmn-js internals. */
export function bpmnWithLabel(label: string): string {
	return MINIMAL_BPMN.replace('name="Start"', `name="${label}"`)
}

const STATIC_ROOT = path.resolve(__dirname, "../src/static")
const BUNDLE_DIR = path.resolve(__dirname, "../../bazel-bin/web/dist/static/js")

const CONTENT_TYPES: Record<string, string> = {
	".html": "text/html",
	".css": "text/css",
	".js": "application/javascript",
	".json": "application/json",
	".svg": "image/svg+xml",
	".woff": "font/woff",
	".woff2": "font/woff2",
	".ttf": "font/ttf",
	".eot": "application/vnd.ms-fontobject",
}

/**
 * Serves the studio from disk through Playwright's routing, so the specs need no web server and
 * no toolchain the repo does not already declare.
 *
 * The source directory holds every asset but the bundle, which only exists once built, so that
 * one path resolves to the Bazel output instead.
 */
export async function serveStudio(page: Page): Promise<void> {
	await page.route("**/*", async (route) => {
		const pathname = new URL(route.request().url()).pathname
		const file = pathname.endsWith("/js/app-bundle.js")
			? path.join(BUNDLE_DIR, bundleName())
			: path.join(STATIC_ROOT, pathname.replace(/^\//, ""))

		if (!fs.existsSync(file)) {
			await route.fulfill({ status: 404, body: "" })
			return
		}
		await route.fulfill({
			contentType:
				CONTENT_TYPES[path.extname(file)] ?? "application/octet-stream",
			body: fs.readFileSync(file),
		})
	})
}

function bundleName(): string {
	const bundle = fs
		.readdirSync(BUNDLE_DIR)
		.find((name) => name.startsWith("app-bundle-") && name.endsWith(".js"))
	if (!bundle)
		throw new Error(
			`No built bundle in ${BUNDLE_DIR} — run: bazel build //web:bundle`,
		)
	return bundle
}

/**
 * Routes every call the studio makes to the backend. Registered after [serveStudio] so these
 * match first: Playwright checks the most recently added route before earlier ones.
 */
export async function stubBackend(
	page: Page,
	updates: Update[],
	options: { answersStatus?: number } = {},
): Promise<{ answers: string[] }> {
	const answers: string[] = []

	await page.route("**/api/bpmn/generations", async (route) => {
		await route.fulfill({
			status: 202,
			contentType: "application/json",
			body: JSON.stringify({
				processId: PROCESS_ID,
				sseUrl: `api/bpmn/generations/${PROCESS_ID}/updates`,
			}),
		})
	})

	await page.route("**/updates", async (route) => {
		await route.fulfill({
			contentType: "text/event-stream",
			body: sse(updates),
		})
	})

	await page.route("**/answers", async (route) => {
		answers.push(route.request().postData() ?? "")
		await route.fulfill({ status: options.answersStatus ?? 202, body: "" })
	})

	await page.route("**/bpmn", async (route) => {
		await route.fulfill({ contentType: "application/xml", body: MINIMAL_BPMN })
	})

	await page.route("**/version.json", async (route) => {
		await route.fulfill({
			contentType: "application/json",
			body: '{"version":"0.5.0"}',
		})
	})

	return { answers }
}

export async function submitDescription(
	page: Page,
	text: string,
): Promise<void> {
	await page.fill("#process-description", text)
	await page.click("#generate-btn")
}
