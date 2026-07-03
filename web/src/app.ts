/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import BpmnViewer from "bpmn-js"
import { layoutProcess } from "yet-another-bpmn-auto-layout"
import { type ClarifyState, renderClarifyForm } from "./clarify-form"
import {
	type ResultBarState,
	type ResultStatus,
	renderResultBar,
} from "./result-bar"
import { importSnapshot } from "./snapshot-import"
import type { ChipState, StageKey } from "./stage-rail"
import { initialStages, reduceStages, renderStageRail } from "./stage-rail"
import { initialSettle, type SettleState, shouldClose } from "./stream-settle"

type ProgressUpdateEvent = {
	type: "ProgressUpdateEvent"
	name: string
}

type BpmnSnapshotEvent = {
	type: "BpmnSnapshotEvent"
	xml?: string
	diagnostics?: Diagnostic[]
	attemptNumber?: number
}

type AgentProcessEvent = {
	type: "AgentProcessFinishedEvent" | "AgentProcessFailedEvent"
}

type BpmnRunCostEvent = {
	type: "BpmnRunCostEvent"
	costSummary: string
}

type BpmnStageEvent = {
	type: "BpmnStageEvent"
	stage: string
	stageStatus: string
	label: string
}

/**
 * Terminal result event — wire contract: class simple name is the type discriminator.
 * `resultStatus` not `status` (ADR-ss-008: AbstractAgentProcessEvent already exposes `status`).
 */
type BpmnResultEvent = {
	type: "BpmnResultEvent"
	resultStatus: string
	alignmentVerdict?: string
	alignmentReport?: string
}

/**
 * Clarification request event — published when the agent parks in AwaitingClarification.
 * Wire contract (ARCHITECTURE.md §ss-4, ADR-ss-008): uses `round`/`maxRounds`/`prompt`,
 * not `status`.
 */
type BpmnClarificationRequestEvent = {
	type: "BpmnClarificationRequestEvent"
	round: number
	maxRounds: number
	prompt: string
}

type ServerEvent =
	| ProgressUpdateEvent
	| BpmnSnapshotEvent
	| AgentProcessEvent
	| BpmnRunCostEvent
	| BpmnStageEvent
	| BpmnResultEvent
	| BpmnClarificationRequestEvent
	| { type?: string }

type Diagnostic = {
	source?: string
	message: string
	elementId?: string
	objectRef?: string
}

type BpmnCanvas = {
	zoom: (mode: "fit-viewport") => void
}

type BpmnOverlays = {
	remove: (filter: { type: string }) => void
	add: (
		elementId: string,
		type: string,
		options: {
			position: { bottom: number; right: number }
			html: HTMLElement
		},
	) => void
}

const viewer = new BpmnViewer({
	container: "#canvas",
})

function getRequiredElement<T extends HTMLElement>(id: string): T {
	const element = document.getElementById(id)
	if (!(element instanceof HTMLElement)) {
		throw new Error(`Missing required element #${id}`)
	}
	return element as T
}

const generateBtn = getRequiredElement<HTMLButtonElement>("generate-btn")
const descriptionEl = getRequiredElement<HTMLTextAreaElement>(
	"process-description",
)
const progressContainer = getRequiredElement<HTMLElement>("progress-container")
const progressList = getRequiredElement<HTMLElement>("progress-list")
const diagnosticsContainer = getRequiredElement<HTMLElement>(
	"diagnostics-container",
)
const diagnosticsList = getRequiredElement<HTMLElement>("diagnostics-list")
const resultBarEl = getRequiredElement<HTMLElement>("result-bar")
const clarifyRegionEl = getRequiredElement<HTMLElement>("clarify-region")
const stageRailEl = getRequiredElement<HTMLElement>("stage-rail")
const canvasStatus = getRequiredElement<HTMLElement>("canvas-status")

let eventSource: EventSource | null = null
let currentXml = ""
let snapshotCount = 0
let settle: SettleState = initialSettle()
let closeTimer: number | null = null
let stages: Record<StageKey, ChipState> = initialStages()
let resultBarState: ResultBarState = {}
/** processId captured from POST response; used to build the BPMN download URL. */
let currentProcessId: string | null = null

// All three terminal signals (BpmnResultEvent, BpmnRunCostEvent, AgentProcessFinishedEvent)
// fan out from the same server-side AgentProcessFinishedEvent, so their SSE order is
// non-deterministic. The grace timer is a safety net for runs that never emit BpmnResultEvent
// (budget-exhausted / stuck) — it closes the stream only when sawResult is still false,
// leaving a real BpmnResultEvent free to arrive and render before the stream closes.
const COST_EVENT_GRACE_MS = 4000

generateBtn.addEventListener("click", async () => {
	const desc = descriptionEl.value.trim()
	if (!desc) return

	generateBtn.disabled = true
	progressContainer.classList.remove("hidden")
	progressList.innerHTML = ""
	progressContainer.querySelectorAll("pre.run-cost").forEach((el) => {
		el.remove()
	})
	diagnosticsContainer.classList.add("hidden")
	clarifyRegionEl.classList.add("hidden")
	clarifyRegionEl.innerHTML = ""
	resultBarState = {}
	renderResultBar(resultBarEl, resultBarState)
	currentXml = ""
	currentProcessId = null
	snapshotCount = 0
	settle = initialSettle()
	stages = initialStages()
	renderStageRail(stageRailEl, stages)
	canvasStatus.textContent = ""
	canvasStatus.classList.add("hidden")
	if (closeTimer !== null) {
		clearTimeout(closeTimer)
		closeTimer = null
	}
	viewer.clear()

	if (eventSource) {
		eventSource.close()
	}

	try {
		const res = await fetch("api/bpmn/generations", {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify({ processDescription: desc }),
		})

		if (!res.ok) {
			throw new Error(`Failed to start generation: ${res.statusText}`)
		}

		const data = await res.json()
		currentProcessId = data.processId as string | null
		connectSse(data.sseUrl)
	} catch (e: unknown) {
		const message = e instanceof Error ? e.message : String(e)
		addProgress(`Error: ${message}`)
		generateBtn.disabled = false
	}
})

function connectSse(url: string) {
	eventSource = new EventSource(url)

	eventSource.onmessage = async (e) => {
		let event: ServerEvent
		try {
			event = JSON.parse(e.data) as ServerEvent
		} catch (err) {
			console.error("Failed to parse SSE message", err)
			return
		}

		if (event.type === "ProgressUpdateEvent" && "name" in event) {
			addProgress(event.name as string)
		} else if (event.type === "BpmnStageEvent") {
			applyStageEvent(event as BpmnStageEvent)
		} else if (event.type === "BpmnSnapshotEvent" && "xml" in event) {
			await handleSnapshot(event as BpmnSnapshotEvent)
		} else if (event.type === "BpmnClarificationRequestEvent") {
			applyClarificationEvent(event as BpmnClarificationRequestEvent)
		} else if (event.type === "BpmnResultEvent") {
			applyResultEvent(event as BpmnResultEvent)
		} else if (event.type === "AgentProcessFinishedEvent") {
			addProgress("Process complete.")
			generateBtn.disabled = false
			clarifyRegionEl.classList.add("hidden")
			clarifyRegionEl.innerHTML = ""
			settle = { ...settle, sawFinish: true }
			if (closeTimer === null) {
				closeTimer = window.setTimeout(() => {
					// Safety net for stuck/failed runs that never emit BpmnResultEvent.
					// Only closes if the real result event has not yet arrived; if it has,
					// applyResultEvent() already called closeWhenSettled() and this is a no-op.
					if (!settle.sawResult) {
						closeStream()
					}
				}, COST_EVENT_GRACE_MS)
			}
			closeWhenSettled()
		} else if (event.type === "BpmnRunCostEvent" && "costSummary" in event) {
			const costEvent = event as BpmnRunCostEvent
			// Render cost in the progress ticker (existing behaviour) and in the result bar.
			renderCostSummary(costEvent.costSummary)
			resultBarState = { ...resultBarState, costSummary: costEvent.costSummary }
			renderResultBar(resultBarEl, resultBarState)
			settle = { ...settle, sawCost: true }
			closeWhenSettled()
		} else if (event.type === "AgentProcessFailedEvent") {
			addProgress("Process failed.")
			generateBtn.disabled = false
			closeStream()
		}
	}

	eventSource.onerror = (e) => {
		console.error("SSE Error", e)
		closeStream()
		generateBtn.disabled = false
		addProgress("Connection lost.")
	}
}

function applyStageEvent(event: BpmnStageEvent): void {
	stages = reduceStages(stages, {
		stage: event.stage,
		status: event.stageStatus,
	})
	renderStageRail(stageRailEl, stages)
}

function applyClarificationEvent(event: BpmnClarificationRequestEvent): void {
	const baseState: ClarifyState = {
		prompt: event.prompt,
		round: event.round,
		maxRounds: event.maxRounds,
		submitting: false,
	}

	async function submitAnswers(answer: string): Promise<void> {
		// Optimistically disable the form while submitting.
		renderClarifyForm(
			clarifyRegionEl,
			{ ...baseState, submitting: true },
			submitAnswers,
		)

		try {
			const res = await fetch(
				`api/bpmn/generations/${currentProcessId}/answers`,
				{
					method: "POST",
					headers: { "Content-Type": "application/json" },
					body: JSON.stringify({ answers: answer }),
				},
			)

			if (res.status === 202) {
				// Resume accepted — hide the form; progress resumes over SSE.
				// A round-2 BpmnClarificationRequestEvent will re-show it if needed.
				clarifyRegionEl.classList.add("hidden")
				clarifyRegionEl.innerHTML = ""
			} else {
				// Non-202: show inline error and re-enable.
				renderClarifyForm(
					clarifyRegionEl,
					{
						...baseState,
						submitting: false,
						error: "Couldn't submit — try again",
					},
					submitAnswers,
				)
			}
		} catch {
			renderClarifyForm(
				clarifyRegionEl,
				{
					...baseState,
					submitting: false,
					error: "Couldn't submit — try again",
				},
				submitAnswers,
			)
		}
	}

	renderClarifyForm(clarifyRegionEl, baseState, submitAnswers)
}

function applyResultEvent(event: BpmnResultEvent): void {
	const status = event.resultStatus as ResultStatus
	const downloadUrl = currentProcessId
		? `api/bpmn/generations/${currentProcessId}/bpmn`
		: undefined
	// Hide the clarify region on terminal result (covers NEEDS_CLARIFICATION after round 3).
	clarifyRegionEl.classList.add("hidden")
	clarifyRegionEl.innerHTML = ""
	resultBarState = {
		...resultBarState,
		status,
		alignmentVerdict: event.alignmentVerdict,
		alignmentReport: event.alignmentReport,
		downloadUrl,
	}
	renderResultBar(resultBarEl, resultBarState)
	// Mark result seen and attempt settlement — fixes the F1 race where the stream
	// could close on sawFinish+sawCost before BpmnResultEvent arrived (REVIEW-ss-3).
	settle = { ...settle, sawResult: true }
	closeWhenSettled()
}

function closeStream() {
	if (closeTimer !== null) {
		clearTimeout(closeTimer)
		closeTimer = null
	}
	eventSource?.close()
}

function closeWhenSettled() {
	if (shouldClose(settle)) {
		closeStream()
	}
}

function renderCostSummary(summary: string) {
	const pre = document.createElement("pre")
	pre.className = "run-cost"
	pre.textContent = summary
	progressContainer.appendChild(pre)
}

function addProgress(msg: string) {
	const li = document.createElement("li")
	li.textContent = msg
	progressList.appendChild(li)
}

async function handleSnapshot(event: BpmnSnapshotEvent) {
	currentXml = event.xml || ""
	if (!currentXml) return

	const outcome = await importSnapshot(
		{
			layout: layoutProcess,
			importXML: (xml) => viewer.importXML(xml),
		},
		currentXml,
		event.attemptNumber,
	)

	if (outcome.status === "drawn") {
		snapshotCount += 1
		if (snapshotCount === 1) {
			;(viewer.get("canvas") as BpmnCanvas).zoom("fit-viewport")
		}
		canvasStatus.textContent = ""
		canvasStatus.classList.add("hidden")
	} else {
		const msg =
			outcome.attemptNumber !== undefined
				? `Diagram pending (attempt ${outcome.attemptNumber})`
				: "Diagram pending"
		canvasStatus.textContent = msg
		canvasStatus.classList.remove("hidden")
	}

	// Always update diagnostics list — it is canvas-independent.
	// overlays.add already silently catches missing elements.
	renderDiagnostics(event.diagnostics || [])
}

function renderDiagnostics(diagnostics: Diagnostic[]) {
	diagnosticsList.innerHTML = ""
	const overlays = viewer.get("overlays") as BpmnOverlays

	// Clear previous overlays
	overlays.remove({ type: "diagnostic" })

	if (diagnostics.length === 0) {
		diagnosticsContainer.classList.add("hidden")
		return
	}

	diagnosticsContainer.classList.remove("hidden")

	diagnostics.forEach((diag) => {
		const li = document.createElement("li")
		li.className = "diagnostic-item"
		li.textContent = `[${diag.source}] ${diag.message}`
		diagnosticsList.appendChild(li)

		// If we have an element ID, we can overlay it
		const elementId = diag.elementId || diag.objectRef
		if (elementId) {
			try {
				const overlayHtml = document.createElement("div")
				overlayHtml.className = "diagnostic-overlay"
				overlayHtml.title = diag.message

				overlays.add(elementId, "diagnostic", {
					position: {
						bottom: 0,
						right: 0,
					},
					html: overlayHtml,
				})
			} catch {
				// Element might not exist in the diagram yet
			}
		}
	})
}
