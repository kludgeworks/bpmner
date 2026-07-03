/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * State for the clarification form, built from BpmnClarificationRequestEvent.
 * Wire contract (ARCHITECTURE.md §ss-4): prompt is sourced from FormBindingRequest.payload.title,
 * round/maxRounds from the publisher's per-process counter.
 */
export type ClarifyState = {
	prompt: string
	round: number
	maxRounds: number
	submitting: boolean
	error?: string
}

/**
 * Builds the inner HTML for the clarify form from the given state.
 * Pure function — no side effects. Directly testable without DOM.
 *
 * The prompt is HTML-escaped to prevent XSS. The submit button is disabled
 * while submitting (optimistic disable). An inline error string is rendered
 * when set; no toast library (rung 1 — no new dependency).
 */
export function buildClarifyFormHtml(state: ClarifyState): string {
	const indicator = `Clarification ${state.round} of ${state.maxRounds}`
	const disabledAttr = state.submitting ? " disabled" : ""

	let html = `<p class="clarify-indicator">${escapeHtml(indicator)}</p>`
	html += `<p class="clarify-prompt">${escapeHtml(state.prompt)}</p>`
	html += `<textarea class="clarify-answer" rows="4" placeholder="Enter your answer…"${disabledAttr}></textarea>`
	html += `<button class="clarify-submit" type="button"${disabledAttr}>Submit answer</button>`

	if (state.error) {
		html += `<p class="clarify-error">${escapeHtml(state.error)}</p>`
	}

	return html
}

/**
 * Renders the clarify form into the given container, wiring up the submit handler.
 * Shows the container; call with a hidden container to unmount (hide + clear innerHTML separately).
 *
 * @param container - The host element (e.g. #clarify-region).
 * @param state     - Current form state.
 * @param onSubmit  - Called with the trimmed answer text when the user clicks Submit.
 */
export function renderClarifyForm(
	container: HTMLElement,
	state: ClarifyState,
	onSubmit: (answer: string) => void,
): void {
	container.innerHTML = buildClarifyFormHtml(state)
	container.classList.remove("hidden")

	const btn = container.querySelector<HTMLButtonElement>(".clarify-submit")
	const textarea =
		container.querySelector<HTMLTextAreaElement>(".clarify-answer")

	if (btn && textarea) {
		btn.addEventListener("click", () => {
			const answer = textarea.value.trim()
			if (answer) {
				onSubmit(answer)
			}
		})
	}
}

function escapeHtml(text: string): string {
	return text
		.replace(/&/g, "&amp;")
		.replace(/</g, "&lt;")
		.replace(/>/g, "&gt;")
		.replace(/"/g, "&quot;")
}
