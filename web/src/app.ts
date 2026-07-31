/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import BpmnViewer from "bpmn-js"
import {
	buildClarifyState,
	buildResultBarState,
	parseRunUpdate,
} from "./app-helpers"
import {
	bindZoomControls,
	type CanvasViewport,
	fitInitialViewport,
	setZoomControlsEnabled,
} from "./canvas-viewport"
import { renderClarifyForm } from "./clarify-form"
import { type ResultBarState, renderResultBar } from "./result-bar"
import { isTerminal, type RunUpdate } from "./run-update"
import { importSnapshot } from "./snapshot-import"
import type { ChipState, StageKey } from "./stage-rail"
import { initialStages, reduceStages, renderStageRail } from "./stage-rail"
import { shouldClose } from "./stream-settle"
import { populateVersionFooter } from "./version-footer"

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
const resultBarEl = getRequiredElement<HTMLElement>("result-bar")
const clarifyRegionEl = getRequiredElement<HTMLElement>("clarify-region")
const stageRailEl = getRequiredElement<HTMLElement>("stage-rail")
const canvasStatus = getRequiredElement<HTMLElement>("canvas-status")
const canvasEl = getRequiredElement<HTMLElement>("canvas")
const zoomInBtn = getRequiredElement<HTMLButtonElement>("zoom-in-btn")
const zoomOutBtn = getRequiredElement<HTMLButtonElement>("zoom-out-btn")
const canvasViewport = viewer.get("canvas") as CanvasViewport
bindZoomControls(canvasViewport, zoomInBtn, zoomOutBtn)
// Optional version footer (absent → no-op).
const versionFooterEl = document.getElementById("version-footer")
if (versionFooterEl) {
	void populateVersionFooter(versionFooterEl)
}

let eventSource: EventSource | null = null
let stages: Record<StageKey, ChipState> = initialStages()
let resultBarState: ResultBarState = {}
/** processId captured from POST response; used to build the BPMN download/preview URL. */
let currentProcessId: string | null = null

generateBtn.addEventListener("click", async () => {
	const desc = descriptionEl.value.trim()
	if (!desc) return

	generateBtn.disabled = true
	descriptionEl.disabled = true
	progressContainer.classList.remove("hidden")
	progressList.innerHTML = ""
	clarifyRegionEl.classList.add("hidden")
	clarifyRegionEl.innerHTML = ""
	resultBarState = {}
	renderResultBar(resultBarEl, resultBarState)
	currentProcessId = null
	setZoomControlsEnabled(zoomInBtn, zoomOutBtn, false)
	stages = initialStages()
	renderStageRail(stageRailEl, stages)
	canvasStatus.textContent = ""
	canvasStatus.classList.add("hidden")
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
		descriptionEl.disabled = false
	}
})

function connectSse(url: string) {
	eventSource = new EventSource(url)

	eventSource.onmessage = (e: MessageEvent) => {
		const update = parseRunUpdate(e.data)
		if (!update) {
			console.error("Failed to parse RunUpdate", e.data)
			return
		}

		addProgress(update.summary)

		stages = reduceStages(stages, update)
		renderStageRail(stageRailEl, stages)

		if (!isTerminal(update) && update.phase === "AWAITING_INPUT") {
			applyClarificationEvent(update)
			return
		}

		if (isTerminal(update)) {
			void applyTerminalUpdate(update)
		}
	}

	eventSource.onerror = (e) => {
		console.error("SSE Error", e)
		closeStream()
		generateBtn.disabled = false
		descriptionEl.disabled = false
		addProgress("Connection lost.")
	}
}

function applyClarificationEvent(update: RunUpdate): void {
	const baseState = buildClarifyState(update)

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
				// A subsequent AWAITING_INPUT update will re-show it if needed.
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

/**
 * Handles the one terminal `RunUpdate` for a run: renders the result bar from its whitelisted
 * `detail`, fetches and imports the final diagram when one exists (`artifactState !== "NONE"`
 * — `GET /generations/{id}/bpmn`, the only XML the browser ever receives), and closes the
 * stream.
 */
async function applyTerminalUpdate(update: RunUpdate): Promise<void> {
	generateBtn.disabled = false
	descriptionEl.disabled = false
	clarifyRegionEl.classList.add("hidden")
	clarifyRegionEl.innerHTML = ""

	resultBarState = buildResultBarState(update, currentProcessId)
	renderResultBar(resultBarEl, resultBarState)

	if (resultBarState.downloadUrl) {
		await loadFinalDiagram(resultBarState.downloadUrl)
	}

	if (shouldClose(update)) {
		closeStream()
	}
}

async function loadFinalDiagram(url: string): Promise<void> {
	try {
		const res = await fetch(url)
		if (!res.ok) {
			canvasStatus.textContent = "Diagram unavailable"
			canvasStatus.classList.remove("hidden")
			return
		}
		const xml = await res.text()
		const outcome = await importSnapshot(
			{ importXML: (x) => viewer.importXML(x) },
			xml,
		)

		if (outcome.status === "drawn") {
			canvasStatus.textContent = ""
			canvasStatus.classList.add("hidden")
			triggerCanvasEntrance()
			requestAnimationFrame(() => {
				fitInitialViewport(canvasViewport)
				setZoomControlsEnabled(zoomInBtn, zoomOutBtn, true)
			})
		} else {
			canvasStatus.textContent = "Diagram unavailable"
			canvasStatus.classList.remove("hidden")
		}
	} catch (e: unknown) {
		console.error("Failed to load the final diagram", e)
		canvasStatus.textContent = "Diagram unavailable"
		canvasStatus.classList.remove("hidden")
	}
}

function closeStream() {
	eventSource?.close()
}

/**
 * Re-trigger the CSS entrance animation on the freshly-imported diagram.
 * Toggling the class with a forced reflow replays the keyframes each redraw;
 * `@media (prefers-reduced-motion: reduce)` in style.css disables it entirely.
 */
function triggerCanvasEntrance() {
	canvasEl.classList.remove("canvas--entrance")
	// Force reflow so the re-added class restarts the animation.
	void canvasEl.offsetWidth
	canvasEl.classList.add("canvas--entrance")
}

function addProgress(msg: string) {
	const li = document.createElement("li")
	li.textContent = msg
	progressList.appendChild(li)
}
