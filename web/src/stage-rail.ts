/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import type { RunPhase, RunUpdate } from "./run-update"

/**
 * Six deterministic stage keys for the pipeline rail — a client-side grouping of the server's
 * `RunPhase` milestones. Unknown phases (e.g. `AWAITING_INPUT`, a future phase) are silently
 * ignored by the reducer.
 */
export type StageKey =
	| "readiness"
	| "contract"
	| "generate"
	| "validate"
	| "layout"
	| "align"

/** Visual state of a single chip in the rail. */
export type ChipState = "pending" | "active" | "warn" | "done"

/** Ordered stage list — later index = later in the pipeline. */
export const STAGE_ORDER: StageKey[] = [
	"readiness",
	"contract",
	"generate",
	"validate",
	"layout",
	"align",
]

/** Maps a server `RunPhase` onto the rail's coarser `StageKey`, where one exists. */
const PHASE_TO_STAGE: Partial<Record<RunPhase, StageKey>> = {
	READINESS: "readiness",
	CONTRACT: "contract",
	OUTLINE: "generate",
	DRAFT: "generate",
	VALIDATION: "validate",
	LAYOUT: "layout",
	ALIGNMENT: "align",
}

/** Initial state: all chips pending. */
export function initialStages(): Record<StageKey, ChipState> {
	return {
		readiness: "pending",
		contract: "pending",
		generate: "pending",
		validate: "pending",
		layout: "pending",
		align: "pending",
	}
}

/**
 * Pure reducer: applies one `RunUpdate` to the current chip state map.
 *
 * Rules:
 * - A non-terminal update whose phase maps to a stage: marks that chip `active` (or `warn`
 *   when `artifactState` is `DIAGNOSTIC` — the validate/repair loop), and marks every earlier
 *   stage `done`.
 * - The terminal update (`outcome` present): marks every stage reached so far `done`; a stage
 *   never reached (e.g. `generate` on a `NEEDS_CLARIFICATION` outcome) stays `pending`.
 * - A phase with no stage mapping (`AWAITING_INPUT`, `FINISHED` on a non-terminal update):
 *   no-op — forward-compatible with phases this rail doesn't visualise.
 * - Idempotent: applying the same update twice has no additional effect.
 */
export function reduceStages(
	stages: Record<StageKey, ChipState>,
	update: RunUpdate,
): Record<StageKey, ChipState> {
	if (update.outcome !== undefined) {
		const next = { ...stages }
		for (const key of STAGE_ORDER) {
			if (next[key] !== "pending") next[key] = "done"
		}
		return next
	}

	const stage = PHASE_TO_STAGE[update.phase]
	if (!stage) return stages

	const next = { ...stages }

	if (update.artifactState === "DIAGNOSTIC") {
		// A repair-loop retry only flags its own stage as "warn"; it must not mark earlier
		// stages done.
		next[stage] = "warn"
		return next
	}

	const idx = STAGE_ORDER.indexOf(stage)
	for (let i = 0; i < idx; i++) {
		if (next[STAGE_ORDER[i]] === "pending") next[STAGE_ORDER[i]] = "done"
	}
	next[stage] = "active"
	return next
}

/**
 * Renders the current stage states onto the `<ol id="stage-rail">` chips.
 * Each `<li>` carries `data-stage="<key>"` and gets a `data-state` attribute
 * reflecting its current ChipState — styling is handled in ss-5.
 */
export function renderStageRail(
	container: HTMLElement,
	stages: Record<StageKey, ChipState>,
): void {
	for (const stage of STAGE_ORDER) {
		const chip = container.querySelector<HTMLElement>(`[data-stage="${stage}"]`)
		if (chip) {
			chip.dataset.state = stages[stage]
		}
	}
}
