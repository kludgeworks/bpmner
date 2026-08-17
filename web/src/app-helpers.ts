/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import type { ClarifyState } from "./clarify-form"
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
