/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import type { ClarifyState } from "./clarify-form"
import type { ResultBarState, ResultStatus } from "./result-bar"
import type { RunUpdate } from "./run-update"

/** Parses one SSE message payload as a `RunUpdate`, or null if it isn't valid JSON. */
export function parseRunUpdate(raw: string): RunUpdate | null {
	try {
		return JSON.parse(raw) as RunUpdate
	} catch {
		return null
	}
}

/** Builds the clarify-form state from an `AWAITING_INPUT` `RunUpdate`. */
export function buildClarifyState(update: RunUpdate): ClarifyState {
	const detail = update.detail ?? {}
	return {
		prompt: update.summary,
		options: detail.options ? detail.options.split("|") : [],
		round: Number(detail.round ?? "1"),
		maxRounds: Number(detail.maxRounds ?? "1"),
		submitting: false,
	}
}

/**
 * Builds the result-bar state from the one terminal `RunUpdate` for a run. `downloadUrl` is set
 * only when the run produced an artifact (`artifactState !== "NONE"`) and a process id is known
 * — the client fetches the XML once, from `GET /generations/{id}/bpmn`.
 */
export function buildResultBarState(
	update: RunUpdate,
	processId: string | null,
): ResultBarState {
	const detail = update.detail ?? {}
	const hasArtifact = update.artifactState !== "NONE" && processId !== null

	return {
		status: detail.status as ResultStatus | undefined,
		alignmentVerdict: detail.alignmentVerdict,
		alignmentReport: detail.alignmentReport,
		diagnosticsSummary: detail.diagnostics,
		downloadUrl: hasArtifact
			? `api/bpmn/generations/${processId}/bpmn`
			: undefined,
	}
}
