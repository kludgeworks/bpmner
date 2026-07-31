/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * The `RunUpdate` wire contract: a single flat JSON shape serialized directly from the Kotlin
 * sealed interface `dev.groknull.bpmner.pipeline.RunUpdate`, with no class-name `type`
 * discriminator. A `Progress` update omits `outcome`; the one `Terminal` update per run carries
 * it — that presence/absence *is* the single terminal marker.
 */

export type ArtifactState = "NONE" | "XML_DRAFT" | "DIAGNOSTIC" | "FINAL"

export type RunPhase =
	| "READINESS"
	| "AWAITING_INPUT"
	| "CONTRACT"
	| "OUTLINE"
	| "DRAFT"
	| "VALIDATION"
	| "LAYOUT"
	| "ALIGNMENT"
	| "FINISHED"

export type RunOutcome = "COMPLETED" | "FAILED"

export type RunUpdate = {
	seq: number
	phase: RunPhase
	artifactState: ArtifactState
	summary: string
	detail?: Record<string, string>
	/** Present only on the one terminal update for a run. */
	outcome?: RunOutcome
}

/** True once a [RunUpdate] carries the single terminal marker. */
export function isTerminal(update: RunUpdate): boolean {
	return update.outcome !== undefined
}

/**
 * True when `update` should replace `current` in a client's rendered state — it is strictly
 * newer by `seq`. Mirrors the server-side stale-diagram guard
 * (`dev.groknull.bpmner.pipeline.RunUpdate.supersedes`): `seq` is assigned by a single writer
 * per process, so it is a total order regardless of arrival order.
 */
export function supersedes(
	update: RunUpdate,
	current: RunUpdate | null,
): boolean {
	return current === null || update.seq > current.seq
}
