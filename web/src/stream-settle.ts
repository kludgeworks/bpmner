/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Tracks which of the three terminal SSE signals have arrived for the current run.
 *
 * All three must be seen before the EventSource is safe to close (REVIEW-ss-3 F1):
 * `BpmnResultEvent`, `BpmnRunCostEvent`, and `AgentProcessFinishedEvent` all fan out
 * from the same server-side `AgentProcessFinishedEvent` via `AgenticEventListener`s,
 * so their SSE order is non-deterministic. Closing on `sawFinish && sawCost` alone can
 * drop `BpmnResultEvent`, silently leaving the result bar and download link unrendered.
 *
 * Runs that never produce a `BpmnResult` (budget-exhausted / stuck) never emit
 * `BpmnResultEvent`, so `sawResult` stays false; the caller must arm a fallback timer
 * (`COST_EVENT_GRACE_MS`) that forces `sawResult` and calls `closeWhenSettled()` as a
 * safety net, ensuring the stream closes even when no result event arrives (REVIEW-ss-3 F4).
 */
export type SettleState = {
	sawFinish: boolean
	sawCost: boolean
	sawResult: boolean
}

/** Returns the reset state for a new generation run. */
export function initialSettle(): SettleState {
	return { sawFinish: false, sawCost: false, sawResult: false }
}

/**
 * Returns true when all three terminal signals have arrived and the stream may close.
 *
 * Pure function — no side effects, directly testable.
 */
export function shouldClose(state: SettleState): boolean {
	return state.sawFinish && state.sawCost && state.sawResult
}
