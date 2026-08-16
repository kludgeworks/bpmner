/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import {
	type DisplayRow,
	displayRows,
	groupRows,
	PHASE_ESTIMATE,
	type RunState,
	rowFacts,
	rowLabel,
} from "./run-model"

/**
 * Rendering for the run view. Every function takes the element it writes into, so each is
 * drivable from a test without booting the studio.
 */

export type View = "compose" | "run" | "result"

/** `m:ss` from milliseconds, the format every elapsed readout uses. */
export function formatElapsed(ms: number): string {
	const total = Math.max(0, Math.floor(ms / 1000))
	return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`
}

/**
 * The sub-line under the active step: how long it has been running, and what to expect. A stage
 * whose two observed runs disagree is reported as a spread, never as a point estimate — a
 * prediction that is wrong by 2.5x is worse than none.
 */
export function paceText(row: DisplayRow, elapsedMs: number): string {
	const estimate = PHASE_ESTIMATE[row.phase]
	const elapsed = `${formatElapsed(elapsedMs)} elapsed`
	if (!estimate) return elapsed
	return estimate.varies
		? `${elapsed} · this stage varies — ${estimate.low}–${estimate.high}s observed`
		: `${elapsed} · typically ${estimate.low}–${estimate.high}s`
}

/** The right-hand column of a step row: live elapsed, final duration, or the pending estimate. */
export function stepTime(row: DisplayRow, now: number): string {
	if (row.projected) {
		const estimate = PHASE_ESTIMATE[row.phase]
		return estimate ? `~${estimate.low}s` : ""
	}
	if (row.state === "done") {
		return row.ended === undefined ? "" : formatElapsed(row.ended - row.started)
	}
	return formatElapsed(now - row.started)
}

export function renderPipeline(
	container: HTMLElement,
	state: RunState,
	now: number,
): void {
	const { groups, tail } = groupRows(displayRows(state))
	container.replaceChildren(
		...groups.map((group) => {
			const li = document.createElement("li")
			li.className = "pipeline-group"
			li.dataset.group = group.key
			li.dataset.state = group.state

			const head = document.createElement("div")
			head.className = "pipeline-group-head"
			const title = document.createElement("h3")
			title.className = "pipeline-group-title"
			title.textContent = group.title
			const subtitle = document.createElement("p")
			subtitle.className = "tag"
			subtitle.textContent = group.subtitle
			head.append(title, subtitle)

			const steps = document.createElement("ol")
			steps.className = "pipeline-steps"
			steps.append(...group.rows.map((row) => stepElement(row, now)))

			li.append(head, steps)
			return li
		}),
		...tail.map((row) => {
			const li = document.createElement("li")
			li.className = "pipeline-group"
			li.dataset.state = row.state
			const steps = document.createElement("ol")
			steps.className = "pipeline-steps"
			steps.append(stepElement(row, now))
			li.append(steps)
			return li
		}),
	)
}

function stepElement(row: DisplayRow, now: number): HTMLElement {
	const li = document.createElement("li")
	li.className = "pipeline-step"
	li.dataset.phase = row.phase
	li.dataset.state = row.projected ? "pending" : row.state

	const dot = document.createElement("span")
	dot.className = "pipeline-step-dot"
	dot.setAttribute("aria-hidden", "true")

	const label = document.createElement("span")
	label.className = "pipeline-step-label"
	label.textContent = rowLabel(row)

	const time = document.createElement("span")
	time.className = "pipeline-step-time"
	time.textContent = stepTime(row, now)

	li.append(dot, label, time)

	const facts = rowFacts(row)
	if (facts.length > 0) {
		const factsEl = document.createElement("p")
		factsEl.className = "pipeline-step-facts"
		factsEl.textContent = facts.join(" · ")
		li.append(factsEl)
	}
	return li
}

/** Appends one telemetry line. `kind` drives styling; the log is never rebuilt wholesale. */
export function appendTelemetry(
	log: HTMLElement,
	kind: "client" | "event" | "narration",
	elapsedMs: number,
	text: string,
): void {
	const li = document.createElement("li")
	li.dataset.kind = kind
	if (kind !== "narration") {
		const time = document.createElement("time")
		time.textContent = formatElapsed(elapsedMs)
		li.append(time)
	}
	const body = document.createElement("span")
	body.textContent = text
	li.append(body)
	log.append(li)
	log.scrollTop = log.scrollHeight
}
