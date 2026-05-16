/**
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import BpmnViewer from "bpmn-js"

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

type ServerEvent =
	| ProgressUpdateEvent
	| BpmnSnapshotEvent
	| AgentProcessEvent
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

let eventSource: EventSource | null = null
let currentXml = ""
let currentDownloadUrl: string | null = null
let snapshotCount = 0

generateBtn.addEventListener("click", async () => {
	const desc = descriptionEl.value.trim()
	if (!desc) return

	generateBtn.disabled = true
	progressContainer.classList.remove("hidden")
	progressList.innerHTML = ""
	diagnosticsContainer.classList.add("hidden")
	downloadContainer.classList.add("hidden")
	currentXml = ""
	snapshotCount = 0
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

		if (event.type === "ProgressUpdateEvent") {
			addProgress(event.name)
		} else if (event.type === "BpmnSnapshotEvent") {
			await handleSnapshot(event)
		} else if (event.type === "AgentProcessFinishedEvent") {
			addProgress("Process complete.")
			if (currentXml) {
				setupDownload(currentXml)
			} else {
				addProgress("No BPMN XML was generated.")
			}
			generateBtn.disabled = false
			eventSource?.close()
		} else if (event.type === "AgentProcessFailedEvent") {
			addProgress("Process failed.")
			generateBtn.disabled = false
			eventSource?.close()
		}
	}

	eventSource.onerror = (e) => {
		console.error("SSE Error", e)
		eventSource?.close()
		generateBtn.disabled = false
		addProgress("Connection lost.")
	}
}

function addProgress(msg: string) {
	const li = document.createElement("li")
	li.textContent = msg
	progressList.appendChild(li)
}

async function handleSnapshot(event: BpmnSnapshotEvent) {
	currentXml = event.xml
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
