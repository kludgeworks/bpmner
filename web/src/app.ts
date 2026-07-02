/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import BpmnViewer from "bpmn-js"
import type { ChipState, StageKey } from "./stage-rail"
import { initialStages, reduceStages, renderStageRail } from "./stage-rail"

type ProgressUpdateEvent = {
	type: "ProgressUpdateEvent"
	name: string
}

type BpmnSnapshotEvent = {
	type: "BpmnSnapshotEvent"
	xml?: string
	diagnostics?: Diagnostic[]
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

type ServerEvent =
	| ProgressUpdateEvent
	| BpmnSnapshotEvent
	| AgentProcessEvent
	| BpmnRunCostEvent
	| BpmnStageEvent
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
const downloadContainer = getRequiredElement<HTMLElement>("download-container")
const downloadXml = getRequiredElement<HTMLAnchorElement>("download-xml")
const stageRailEl = getRequiredElement<HTMLElement>("stage-rail")

let eventSource: EventSource | null = null
let currentXml = ""
let currentDownloadUrl: string | null = null
let snapshotCount = 0
let sawFinish = false
let sawCost = false
let closeTimer: number | null = null
let stages: Record<StageKey, ChipState> = initialStages()

// The run-cost event arrives just after the terminal finished event, so keep the stream open
// briefly past completion to receive it; close anyway after this grace period if it never comes.
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
	downloadContainer.classList.add("hidden")
	currentXml = ""
	snapshotCount = 0
	sawFinish = false
	sawCost = false
	stages = initialStages()
	renderStageRail(stageRailEl, stages)
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
		} else if (event.type === "BpmnStageEvent" && "stageStatus" in event) {
			const stageEvent = event as BpmnStageEvent
			stages = reduceStages(stages, {
				stage: stageEvent.stage,
				status: stageEvent.stageStatus,
			})
			renderStageRail(stageRailEl, stages)
		} else if (event.type === "BpmnSnapshotEvent" && "xml" in event) {
			await handleSnapshot(event as BpmnSnapshotEvent)
		} else if (event.type === "AgentProcessFinishedEvent") {
			addProgress("Process complete.")
			if (currentXml) {
				setupDownload(currentXml)
			} else {
				addProgress("No BPMN XML was generated.")
			}
			generateBtn.disabled = false
			sawFinish = true
			if (closeTimer === null) {
				closeTimer = window.setTimeout(closeStream, COST_EVENT_GRACE_MS)
			}
			closeWhenSettled()
		} else if (event.type === "BpmnRunCostEvent" && "costSummary" in event) {
			renderCostSummary((event as BpmnRunCostEvent).costSummary)
			sawCost = true
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

function closeStream() {
	if (closeTimer !== null) {
		clearTimeout(closeTimer)
		closeTimer = null
	}
	eventSource?.close()
}

function closeWhenSettled() {
	if (sawFinish && sawCost) {
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

	try {
		const { warnings } = await viewer.importXML(currentXml)
		if (warnings.length) {
			console.warn("Warnings importing XML", warnings)
		}

		snapshotCount += 1
		if (snapshotCount === 1) {
			;(viewer.get("canvas") as BpmnCanvas).zoom("fit-viewport")
		}

		renderDiagnostics(event.diagnostics || [])
	} catch (err) {
		console.error("Error rendering XML", err)
	}
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

function setupDownload(xml: string) {
	downloadContainer.classList.remove("hidden")
	if (currentDownloadUrl) {
		URL.revokeObjectURL(currentDownloadUrl)
	}
	const blob = new Blob([xml], { type: "application/bpmn20-xml" })
	currentDownloadUrl = URL.createObjectURL(blob)
	downloadXml.href = currentDownloadUrl
}
