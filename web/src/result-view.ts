/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { type Occurrence, type RunState, rowLabel } from "./run-model"
import type { RunUpdate } from "./run-update"

/**
 * The run summary: what was built, where the time went, and how faithful the result is.
 *
 * Every figure here is derived from what the run already delivered — the `detail` maps on the
 * updates, and the occurrence start/end times the client recorded itself. Nothing is asked of
 * the server that it does not already send.
 */

export type Metric = { label: string; value: string }
export type Timing = { label: string; ms: number; share: number }

/**
 * The headline counts, in the order they become known. `repairAttempts` is counted from the
 * `DIAGNOSTIC` updates seen rather than read from a single update: `attemptNumber` rides only a
 * failing validation, so the passing one carries no total.
 */
export function buildMetrics(state: RunState): Metric[] {
	const metrics: Metric[] = []
	const detailFor = (phase: Occurrence["phase"]) =>
		state.occurrences.find((row) => row.phase === phase && row.detail)?.detail

	const outline = detailFor("OUTLINE")
	if (outline?.nodeCount)
		metrics.push({ label: "Activities", value: outline.nodeCount })
	if (outline?.edgeCount)
		metrics.push({ label: "Connections", value: outline.edgeCount })

	const contract = detailFor("CONTRACT")
	if (contract?.issueCount)
		metrics.push({ label: "Contract issues", value: contract.issueCount })
	if (outline?.conformanceCorrections) {
		metrics.push({
			label: "Auto-corrections",
			value: outline.conformanceCorrections,
		})
	}

	const repairs = state.occurrences.filter(
		(row) => row.phase === "VALIDATION" && row.attempt !== undefined,
	).length
	if (repairs > 0)
		metrics.push({ label: "Repair attempts", value: String(repairs) })
	return metrics
}

/**
 * Per-stage durations, from the client's own occurrence timings.
 *
 * The server's per-action figures are not usable for this: they measure the action frame, not
 * the model call inside it, so a stage that took twelve seconds is reported as twelve
 * milliseconds. The gap between updates is the only honest source.
 */
export function buildTimings(state: RunState): Timing[] {
	const timings = state.occurrences
		.filter((row) => row.ended !== undefined)
		.map((row) => ({
			label: rowLabel(row),
			ms: (row.ended as number) - row.started,
		}))
	const longest = timings.reduce((max, timing) => Math.max(max, timing.ms), 0)
	return timings.map((timing) => ({
		...timing,
		share: longest === 0 ? 0 : timing.ms / longest,
	}))
}

export type Alignment = { verdict: string; rationale: string } | null

export function alignmentOf(terminal: RunUpdate | null): Alignment {
	const detail = terminal?.detail
	if (!detail?.alignmentVerdict) return null
	return {
		verdict: detail.alignmentVerdict.replace(/_/g, " ").toLowerCase(),
		rationale: detail.alignmentReport ?? "",
	}
}

export function diagnosticsOf(terminal: RunUpdate | null): string | null {
	return terminal?.detail?.diagnostics ?? null
}

/** Renders the whole panel. Sections with nothing to say are omitted, not left empty. */
export function renderSummary(container: HTMLElement, state: RunState): void {
	const sections: HTMLElement[] = []

	const metrics = buildMetrics(state)
	if (metrics.length > 0) {
		sections.push(
			section("What was built", (body) => {
				const grid = document.createElement("div")
				grid.className = "metric-grid"
				for (const metric of metrics) {
					const card = document.createElement("div")
					card.className = "metric-card"
					const value = document.createElement("p")
					value.className = "metric-value"
					value.textContent = metric.value
					const label = document.createElement("p")
					label.className = "tag"
					label.textContent = metric.label
					card.append(value, label)
					grid.append(card)
				}
				body.append(grid)
			}),
		)
	}

	const timings = buildTimings(state)
	if (timings.length > 0) {
		sections.push(
			section("Where the time went", (body) => {
				const list = document.createElement("ol")
				list.className = "timing-list"
				for (const timing of timings) {
					const li = document.createElement("li")
					li.className = "timing-row"
					const label = document.createElement("span")
					label.className = "timing-label"
					label.textContent = timing.label
					const track = document.createElement("span")
					track.className = "timing-track"
					const fill = document.createElement("span")
					fill.className = "timing-fill"
					fill.style.width = `${Math.round(timing.share * 100)}%`
					track.append(fill)
					const value = document.createElement("span")
					value.className = "timing-value"
					value.textContent = `${(timing.ms / 1000).toFixed(1)}s`
					li.append(label, track, value)
					list.append(li)
				}
				body.append(list)
				const note = document.createElement("p")
				note.className = "summary-note"
				note.textContent =
					"Measured between updates — the only timing the run reports."
				body.append(note)
			}),
		)
	}

	const alignment = alignmentOf(state.terminal)
	if (alignment) {
		sections.push(
			section("Alignment", (body) => {
				const verdict = document.createElement("p")
				verdict.className = "tag"
				verdict.textContent = `verdict ${alignment.verdict}`
				const rationale = document.createElement("p")
				rationale.className = "summary-prose"
				rationale.textContent = alignment.rationale
				body.append(verdict, rationale)
			}),
		)
	}

	const diagnostics = diagnosticsOf(state.terminal)
	if (diagnostics) {
		sections.push(
			section("Diagnostics", (body) => {
				const pre = document.createElement("pre")
				pre.className = "summary-diagnostics"
				pre.textContent = diagnostics
				body.append(pre)
			}),
		)
	}

	container.replaceChildren(...sections)
}

function section(
	title: string,
	fill: (body: HTMLElement) => void,
): HTMLElement {
	const element = document.createElement("section")
	element.className = "summary-section"
	const heading = document.createElement("h3")
	heading.className = "summary-title"
	heading.textContent = title
	const body = document.createElement("div")
	fill(body)
	element.append(heading, body)
	return element
}
