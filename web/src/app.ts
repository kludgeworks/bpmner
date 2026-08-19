/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import BpmnModeler from "bpmn-js/lib/Modeler"
import { buildClarifyState, parseRunUpdate } from "./app-helpers"
import {
	bindZoomControls,
	type CanvasViewport,
	fitInitialViewport,
	setZoomControlsEnabled,
} from "./canvas-viewport"
import { renderClarifyForm } from "./clarify-form"
import {
	bindDiagramExports,
	type DiagramExportControls,
	setDiagramExportControlsEnabled,
} from "./diagram-export"
import { renderSummary } from "./result-view"
import {
	answerSubmitted,
	applyUpdate,
	clearPause,
	displayRows,
	initialRunState,
	type RunState,
	rowLabel,
	startRun,
} from "./run-model"
import { isTerminal, type RunUpdate } from "./run-update"
import { importSnapshot } from "./snapshot-import"
import {
	classifyArtifactResponse,
	classifyStreamError,
	describeReconnect,
} from "./stream-lifecycle"
import {
	appendTelemetry,
	formatElapsed,
	paceText,
	renderPipeline,
} from "./studio-dom"
import { populateVersionFooter } from "./version-footer"

const MAX_DESCRIPTION = 10_000
const TICK_MS = 100

function required<T extends HTMLElement>(id: string): T {
	const element = document.getElementById(id)
	if (!(element instanceof HTMLElement))
		throw new Error(`Missing required element #${id}`)
	return element as T
}

const views = {
	compose: required("view-compose"),
	run: required("view-run"),
	result: required("view-result"),
}
const descriptionEl = required<HTMLTextAreaElement>("process-description")
const countEl = required("compose-count")
const composeError = required("compose-error")
const generateBtn = required<HTMLButtonElement>("generate-btn")
const runDesc = required("run-desc")
const runClock = required("run-clock")
const streamBanner = required("stream-banner")
const streamBannerText = required("stream-banner-text")
const nowRunning = required("now-running")
const clarifyRegion = required("clarify-region")
const pipelineEl = required("pipeline")
const telemetryLog = required("telemetry-log")
const telemetryState = required("telemetry-state")
const resultBadge = required("result-badge")
const resultHeadline = required("result-headline")
const resultMeta = required("result-meta")
const newRunBtn = required("new-run-btn")
const canvasEl = required("canvas")
const canvasStatus = required("canvas-status")
const emptyResult = required("empty-result")
const emptyTitle = required("empty-title")
const emptyWhy = required("empty-why")
const emptyBack = required("empty-back")
const srStatus = required("sr-status")
const resultSections = required("result-sections")
const summaryPanel = required("summary-panel")
const summaryToggle = required<HTMLButtonElement>("summary-toggle")

const modeler = new BpmnModeler({ container: canvasEl })
const canvasViewport = modeler.get("canvas") as CanvasViewport
const zoomIn = required<HTMLButtonElement>("zoom-in-btn")
const zoomOut = required<HTMLButtonElement>("zoom-out-btn")
const zoomReset = required<HTMLButtonElement>("zoom-reset-btn")
const exportControls: DiagramExportControls = {
	xml: required("download-diagram-btn"),
	svg: required("download-svg-btn"),
}
bindZoomControls(canvasViewport, zoomIn, zoomOut, zoomReset)
bindDiagramExports(modeler, exportControls)
void populateVersionFooter(required("version-footer"))

let state: RunState = initialRunState()
let eventSource: EventSource | null = null
let processId: string | null = null
let startedAt = 0
/** Milliseconds of wall clock spent parked on the user, excluded from the run clock. */
let pausedFor = 0
let pausedAt: number | null = null
let sawTerminal = false
let announced = ""
/** Set while the transport is down; that time is not the run's, so the clock excludes it. */
let disconnectedAt: number | null = null
/** Bumped on every new run, so a stale async continuation from a previous run is a no-op. */
let runToken = 0

function elapsed(): number {
	if (startedAt === 0) return 0
	const stalled = pausedAt ?? disconnectedAt
	const paused = pausedFor + (stalled === null ? 0 : Date.now() - stalled)
	return Date.now() - startedAt - paused
}

/**
 * Writes the announcement only when the text changes. The run view rewrites its elapsed
 * readouts ten times a second; a live region fed from that would narrate continuously.
 */
function announce(text: string): void {
	if (text === announced) return
	announced = text
	srStatus.textContent = text
}

function showView(view: keyof typeof views): void {
	for (const [name, element] of Object.entries(views)) {
		element.hidden = name !== view
	}
}

/* ── compose ─────────────────────────────────────────────────────────────── */

function refreshCount(): void {
	const length = descriptionEl.value.length
	countEl.textContent = `${length.toLocaleString("en-GB")} / 10 000`
	const over = length - MAX_DESCRIPTION
	composeError.textContent =
		over > 0
			? `${over.toLocaleString("en-GB")} characters over the 10 000 limit.`
			: ""
	generateBtn.disabled = length === 0 || over > 0
}

descriptionEl.addEventListener("input", refreshCount)
descriptionEl.addEventListener("keydown", (event) => {
	if ((event.metaKey || event.ctrlKey) && event.key === "Enter")
		generateBtn.click()
})
refreshCount()

generateBtn.addEventListener("click", async () => {
	const description = descriptionEl.value.trim()
	if (!description) return

	generateBtn.disabled = true
	composeError.textContent = ""

	try {
		const response = await fetch("/api/bpmn/generations", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ processDescription: description }),
		})
		if (!response.ok) throw new Error(`${response.status}`)

		const body = await response.json()
		processId = body.processId as string
		beginRun(description)
		appendTelemetry(
			telemetryLog,
			"client",
			0,
			`POST /api/bpmn/generations → 202 · ${processId}`,
		)
		appendTelemetry(telemetryLog, "client", 0, "GET …/updates → stream open")
		connect(body.sseUrl as string)
	} catch {
		composeError.textContent = "Could not start the run — try again."
		generateBtn.disabled = false
	}
})

function beginRun(description: string): void {
	runToken += 1
	startedAt = Date.now()
	pausedFor = 0
	pausedAt = null
	sawTerminal = false
	announced = ""
	state = startRun(0)
	runDesc.textContent = description
	telemetryLog.replaceChildren()
	clarifyRegion.hidden = true
	clarifyRegion.replaceChildren()
	streamBanner.hidden = true
	canvasStatus.hidden = true
	setCanvasReady(false)
	modeler.clear()
	showView("run")
	renderRun()
}

/* ── run ─────────────────────────────────────────────────────────────────── */

function renderRun(): void {
	const now = elapsed()
	runClock.textContent = formatElapsed(now)
	renderPipeline(pipelineEl, state, now)

	const current = displayRows(state).find(
		(row) =>
			!row.projected && (row.state === "active" || row.state === "repeat"),
	)
	if (!current || state.terminal) {
		nowRunning.replaceChildren()
		return
	}

	const tag = document.createElement("p")
	tag.className = "tag"
	tag.textContent = state.paused ? "Paused — needs you" : "Now running"
	const label = document.createElement("p")
	label.className = "now-running-label"
	label.textContent = state.paused
		? "Waiting for your answer"
		: `${rowLabel(current)}…`
	const sub = document.createElement("p")
	sub.className = "now-running-sub"
	sub.textContent = state.paused
		? "the run resumes the moment you reply"
		: paceText(current, now - current.started)
	nowRunning.replaceChildren(tag, label, sub)
	announce(label.textContent)
}

setInterval(() => {
	if (startedAt === 0 || sawTerminal || state.paused) return
	renderRun()
}, TICK_MS)

function connect(url: string): void {
	const source = new EventSource(url)
	eventSource = source
	source.onmessage = (event) => {
		const update = parseRunUpdate(event.data)
		if (!update) return
		onUpdate(update)
	}
	source.onerror = () => {
		// The server completes the stream right after the terminal update, and EventSource
		// reports a graceful close the same way as a drop — `classifyStreamError` tells a
		// genuine disconnect apart from that, and a permanently closed connection (an evicted
		// run's 404) apart from one the browser will keep retrying.
		const outcome = classifyStreamError(source.readyState, sawTerminal)
		if (outcome === "graceful") return
		if (outcome === "gone") {
			showRunGone()
			return
		}
		if (disconnectedAt === null) disconnectedAt = Date.now()
		streamBannerText.textContent = "Connection lost — reconnecting…"
		streamBanner.hidden = false
		appendTelemetry(
			telemetryLog,
			"client",
			elapsed(),
			"stream error · no terminal seen · reconnecting",
		)
	}
}

function onUpdate(update: RunUpdate): void {
	const before = state.ignored
	state = applyUpdate(state, update, elapsed())
	if (state.ignored > before) return

	appendTelemetry(
		telemetryLog,
		"event",
		elapsed(),
		`seq ${update.seq} · ${update.phase} · ${update.summary}`,
	)

	if (disconnectedAt !== null) {
		pausedFor += Date.now() - disconnectedAt
		disconnectedAt = null
		streamBannerText.textContent = describeReconnect(state.ignored)
		appendTelemetry(
			telemetryLog,
			"client",
			elapsed(),
			describeReconnect(state.ignored),
		)
		setTimeout(() => {
			streamBanner.hidden = true
		}, 2_600)
	}

	if (isTerminal(update)) {
		void finish(update)
		return
	}
	if (update.phase === "AWAITING_INPUT") {
		pausedAt = Date.now()
		showClarify(update)
	}
	renderRun()
}

/* ── clarification ───────────────────────────────────────────────────────── */

function showClarify(update: RunUpdate): void {
	const base = buildClarifyState(update)

	async function submit(answer: string): Promise<void> {
		renderClarifyForm(clarifyRegion, { ...base, submitting: true }, submit)
		try {
			const response = await fetch(
				`/api/bpmn/generations/${processId}/answers`,
				{
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({ answers: answer }),
				},
			)
			if (response.status === 202) {
				clarifyRegion.hidden = true
				clarifyRegion.replaceChildren()
				if (pausedAt !== null) {
					pausedFor += Date.now() - pausedAt
					pausedAt = null
				}
				state = answerSubmitted(state, elapsed())
				appendTelemetry(
					telemetryLog,
					"client",
					elapsed(),
					"POST …/answers → 202 · re-assessing readiness on the same stream",
				)
				renderRun()
				return
			}
			if (response.status === 404) {
				clarifyRegion.hidden = true
				clarifyRegion.replaceChildren()
				showRunGone()
				return
			}
			if (response.status === 409) {
				// The run is no longer parked; the stream carries on without this answer.
				clarifyRegion.hidden = true
				clarifyRegion.replaceChildren()
				if (pausedAt !== null) {
					pausedFor += Date.now() - pausedAt
					pausedAt = null
				}
				state = clearPause(state)
				appendTelemetry(
					telemetryLog,
					"client",
					elapsed(),
					"answer rejected · run not awaiting input",
				)
				renderRun()
				return
			}
			throw new Error(`${response.status}`)
		} catch {
			renderClarifyForm(
				clarifyRegion,
				{ ...base, submitting: false, error: "Couldn't submit — try again" },
				submit,
			)
		}
	}

	clarifyRegion.hidden = false
	renderClarifyForm(clarifyRegion, base, submit)
	announce(`Clarification ${base.round} of ${base.maxRounds}: ${base.prompt}`)
}

/* ── result ──────────────────────────────────────────────────────────────── */

async function finish(update: RunUpdate): Promise<void> {
	// Captured before any await: if the user starts a new run before this one's diagram fetch
	// settles, the continuation below must recognise it no longer belongs to the current run.
	const token = runToken
	// Close synchronously, before any await: the server has already completed the stream, and a
	// close observed while we are fetching would surface as a spurious connection error.
	sawTerminal = true
	eventSource?.close()
	telemetryState.textContent = "Closed"
	appendTelemetry(
		telemetryLog,
		"client",
		elapsed(),
		"terminal update · EventSource closed by client",
	)

	renderRun()
	// The seven `BpmnGenerationStatus` values all carry `detail.status`; the remaining terminals
	// (aborted, platform failure, stuck, no result) don't, and defaulting them to a success
	// status would badge and headline a failed run as generated.
	const status =
		update.detail?.status ??
		(update.outcome === "FAILED" ? "FAILED" : "GENERATED")
	const empty = update.artifactState === "NONE"

	resultBadge.textContent = status.replace(/_/g, " ").toLowerCase()
	resultBadge.dataset.status = status
	resultHeadline.textContent = headlineFor(status, update)
	resultMeta.textContent = `run ${processId ?? "—"} · ${formatElapsed(elapsed())} total`

	if (update.detail?.permalinkId && update.outcome === "COMPLETED") {
		history.replaceState(null, "", `/p/${update.detail.permalinkId}`)
	}

	showView("result")
	announce(resultHeadline.textContent)

	const body = document.querySelector(".result-body") as HTMLElement | null
	if (body) body.dataset.empty = String(empty)
	emptyResult.hidden = !empty

	renderSummary(resultSections, state)

	if (empty) {
		showEmptyResult(update.summary, update.detail?.failureDetail ?? "")
		return
	}
	await loadDiagram(token)
}

function headlineFor(status: string, update: RunUpdate): string {
	const detail = update.detail ?? {}
	switch (status) {
		case "GENERATED":
			return "Diagram ready"
		case "ALIGNMENT_FAILED":
			return `Needs your review — ${detail.alignmentVerdict?.toLowerCase() ?? "not aligned"}`
		case "VALIDATION_FAILED":
			return "Generated, but the diagram does not validate"
		case "LAYOUT_FAILED":
			return "Generated, but the layout could not be applied"
		default:
			return update.summary
	}
}

/**
 * `token` pins this call to the run that started it. `processId` and the modeler are shared,
 * mutable state a later run can overwrite before this fetch or its retry settles — every
 * continuation past an `await` checks the token before touching either.
 */
async function loadDiagram(token: number, retried = false): Promise<void> {
	const id = processId
	try {
		const response = await fetch(`/api/bpmn/generations/${id}/bpmn`)
		if (token !== runToken) return
		const outcome = classifyArtifactResponse(response.status)
		if (outcome === "retry" && !retried) {
			// The endpoint 409s until the run is terminal, so reaching it means we fetched early.
			appendTelemetry(
				telemetryLog,
				"client",
				elapsed(),
				"GET …/bpmn → 409 · retrying once",
			)
			setTimeout(() => {
				if (token === runToken) void loadDiagram(token, true)
			}, 500)
			return
		}
		if (outcome === "gone") {
			showEmptyResult("The diagram is no longer available", GONE_WHY)
			return
		}
		if (outcome !== "ok") {
			canvasStatus.textContent = "Diagram unavailable"
			canvasStatus.hidden = false
			return
		}
		const xml = await response.text()
		const imported = await importSnapshot(
			{ importXML: (x) => modeler.importXML(x) },
			xml,
		)
		if (token !== runToken) return
		if (imported.status !== "drawn") {
			canvasStatus.textContent = "Diagram unavailable"
			canvasStatus.hidden = false
			return
		}
		canvasEl.classList.add("canvas--entrance")
		requestAnimationFrame(() => {
			if (token !== runToken) return
			fitInitialViewport(canvasViewport)
			setCanvasReady(true)
		})
	} catch {
		if (token !== runToken) return
		canvasStatus.textContent = "Diagram unavailable"
		canvasStatus.hidden = false
	}
}

/** Runs are held in memory and bounded, so an id outlives its run. */
const GONE_WHY =
	"Runs are kept in memory only, so this one has been evicted to make room for newer runs. Start a new run from your description."

function showEmptyResult(title: string, why: string): void {
	const body = document.querySelector(".result-body") as HTMLElement | null
	if (body) body.dataset.empty = "true"
	emptyResult.hidden = false
	emptyTitle.textContent = title
	emptyWhy.textContent = why
	showView("result")
	announce(title)
}

function showRunGone(): void {
	sawTerminal = true
	appendTelemetry(
		telemetryLog,
		"client",
		elapsed(),
		"GET …/updates → 404 · run no longer available",
	)
	renderSummary(resultSections, state)
	resultBadge.textContent = "unavailable"
	resultBadge.dataset.status = "CONTRACT_FAILED"
	resultHeadline.textContent = "This run is no longer available"
	resultMeta.textContent = `run ${processId ?? "—"}`
	showEmptyResult("This run is no longer available", GONE_WHY)
}

function setCanvasReady(ready: boolean): void {
	canvasEl.inert = !ready
	canvasEl.classList.toggle("canvas--disabled", !ready)
	setZoomControlsEnabled(zoomIn, zoomOut, zoomReset, ready)
	setDiagramExportControlsEnabled(exportControls, ready)
}

function backToCompose(): void {
	eventSource?.close()
	startedAt = 0
	state = initialRunState()
	if (window.location.pathname !== "/") {
		history.pushState(null, "", "/")
	}
	showView("compose")
	descriptionEl.focus()
	refreshCount()
}

newRunBtn.addEventListener("click", () => {
	descriptionEl.value = ""
	backToCompose()
})
emptyBack.addEventListener("click", backToCompose)

async function hydrateFromPermalink(id: string): Promise<void> {
	resultBadge.textContent = "generated"
	resultBadge.dataset.status = "GENERATED"
	resultHeadline.textContent = "Diagram ready"
	resultMeta.textContent = `permalink: ${id}`
	showView("result")

	const body = document.querySelector(".result-body") as HTMLElement | null
	if (body) body.dataset.empty = "false"
	emptyResult.hidden = true
	canvasStatus.hidden = true

	try {
		const response = await fetch(`/api/bpmn/p/${id}`)
		if (response.status === 200) {
			const xml = await response.text()
			const imported = await importSnapshot(
				{ importXML: (x) => modeler.importXML(x) },
				xml,
			)
			if (imported.status !== "drawn") {
				canvasStatus.textContent = "Diagram unavailable"
				canvasStatus.hidden = false
				return
			}
			canvasEl.classList.add("canvas--entrance")
			requestAnimationFrame(() => {
				canvasEl.classList.remove("canvas--entrance")
				fitInitialViewport(canvasViewport)
				setCanvasReady(true)
			})
		} else {
			showEmptyResult(
				"The diagram is no longer available",
				"Permalink not found or diagram has expired. Start a new run from your description.",
			)
		}
	} catch {
		canvasStatus.textContent = "Diagram unavailable"
		canvasStatus.hidden = false
	}
}

setCanvasReady(false)

const pathMatch = window.location.pathname.match(/^\/p\/([a-z0-9-]+)$/i)
if (pathMatch) {
	void hydrateFromPermalink(pathMatch[1])
} else {
	showView("compose")
}

summaryToggle.addEventListener("click", () => {
	const collapsed = summaryPanel.classList.toggle("collapsed")
	summaryToggle.setAttribute("aria-expanded", String(!collapsed))
	summaryToggle.title = collapsed ? "Show run summary" : "Collapse run summary"
	// The canvas region changes width, so the diagram is re-fitted once the transition settles.
	setTimeout(() => {
		if (!canvasEl.inert) fitInitialViewport(canvasViewport)
	}, 240)
})
