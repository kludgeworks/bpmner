/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Terminal generation statuses — mirrors BpmnGenerationStatus on the server.
 * Wire contract (ARCHITECTURE.md §wire-contract, §ss-3): these are the four valid values
 * carried by BpmnResultEvent.resultStatus.
 */
export type ResultStatus =
	| "GENERATED"
	| "NEEDS_CLARIFICATION"
	| "ALIGNMENT_FAILED"
	| "VALIDATION_FAILED"

/** Accumulated state for the result bar, built from BpmnResultEvent + BpmnRunCostEvent. */
export type ResultBarState = {
	status?: ResultStatus
	alignmentVerdict?: string
	alignmentReport?: string
	costSummary?: string
	downloadUrl?: string
}

const STATUS_LABELS: Record<ResultStatus, string> = {
	GENERATED: "Generated",
	NEEDS_CLARIFICATION: "Needs clarification",
	ALIGNMENT_FAILED: "Alignment failed",
	VALIDATION_FAILED: "Validation failed",
}

/**
 * Returns true if the status represents a successful generation with downloadable XML.
 * Only GENERATED produces a final BPMN artifact.
 */
function hasDownload(status: ResultStatus): boolean {
	return status === "GENERATED"
}

/**
 * Builds the inner HTML for the result bar from the given state.
 * Pure function — no side effects. Used by renderResultBar and directly testable.
 */
export function buildResultBarHtml(state: ResultBarState): string {
	if (!state.status) return ""

	const label = STATUS_LABELS[state.status] ?? state.status
	let html = `<span class="result-badge" data-result-status="${state.status}">${label}</span>`

	if (state.alignmentReport) {
		const verdict = state.alignmentVerdict ? ` (${state.alignmentVerdict})` : ""
		html += `<details class="alignment-report"><summary>Alignment${verdict}</summary><p>${escapeHtml(state.alignmentReport)}</p></details>`
	}

	if (state.costSummary) {
		html += `<pre class="run-cost">${escapeHtml(state.costSummary)}</pre>`
	}

	if (state.downloadUrl && hasDownload(state.status)) {
		html += `<a class="download-bpmn" download href="${escapeHtml(state.downloadUrl)}">Download BPMN XML</a>`
	}

	return html
}

/**
 * Renders the current result bar state into the given container element.
 * Shows the container when a status is present; hides it otherwise.
 * Side-effect only — delegates HTML generation to the pure buildResultBarHtml.
 */
export function renderResultBar(
	container: HTMLElement,
	state: ResultBarState,
): void {
	if (!state.status) {
		container.classList.add("hidden")
		container.innerHTML = ""
		return
	}
	container.innerHTML = buildResultBarHtml(state)
	container.classList.remove("hidden")
}

function escapeHtml(text: string): string {
	return text
		.replace(/&/g, "&amp;")
		.replace(/</g, "&lt;")
		.replace(/>/g, "&gt;")
		.replace(/"/g, "&quot;")
}
