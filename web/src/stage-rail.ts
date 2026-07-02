/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Six deterministic stage keys for the pipeline rail.
 * Wire contract (ARCHITECTURE.md §wire-contract): these are the only valid stage values;
 * unknown values from future server versions are silently ignored by the reducer.
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
 * Pure reducer: applies one BpmnStageEvent to the current chip state map.
 *
 * Rules:
 * - `active`: marks the target chip active; marks every earlier chip `done`.
 * - `done`: marks the target chip done; marks every earlier chip `done`.
 * - `warn`: marks the target chip warn; does not affect other chips.
 * - Unknown stage or status: no-op (forward-compatibility with later stages).
 * - Idempotent: calling with the same event twice has no additional effect.
 */
export function reduceStages(
	stages: Record<StageKey, ChipState>,
	event: { stage: string; status: string },
): Record<StageKey, ChipState> {
	const stage = event.stage as StageKey
	if (!STAGE_ORDER.includes(stage)) return stages

	const status = event.status
	if (status !== "active" && status !== "done" && status !== "warn")
		return stages

	const next = { ...stages }

	if (status === "warn") {
		next[stage] = "warn"
		return next
	}

	// active or done: mark target, mark all earlier stages done
	const idx = STAGE_ORDER.indexOf(stage)
	for (let i = 0; i < idx; i++) {
		next[STAGE_ORDER[i]] = "done"
	}
	next[stage] = status as ChipState
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
