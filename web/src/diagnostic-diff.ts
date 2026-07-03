/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Client-side fixed-diagnostic tracking (ADR-ss-002).
 *
 * The server sends full-state diagnostics per snapshot/attempt — there is no
 * server-side `id` or `fixed` field (ARCHITECTURE.md non-goals). A diagnostic
 * present in attempt N and absent in N+1 *is* "fixed". This module keys
 * diagnostics by (source, rule, elementId, message) and folds successive
 * full-state snapshots into a stable-ordered list: vanished rows are marked
 * `fixed` and kept in place (never reordered), so the UI can strike them
 * through and clear exactly their canvas overlays.
 */

export type Diagnostic = {
	source?: string
	message: string
	elementId?: string
	objectRef?: string
	severity?: string
	rule?: string
}

export type DiagnosticKey = string

/**
 * Stable identity of a diagnostic across attempts (ADR-ss-002 key:
 * `source | rule | elementId | message`, undefined fields → empty segment).
 */
export function keyOf(d: Diagnostic): DiagnosticKey {
	return [d.source ?? "", d.rule ?? "", d.elementId ?? "", d.message].join("|")
}

export type DiffRow = {
	diagnostic: Diagnostic
	fixed: boolean
}

export type DiffState = {
	rows: DiffRow[]
	attemptNumber: number
}

export type DiffResult = {
	state: DiffState
	/**
	 * Keys that transitioned to `fixed` in this diff (were live, now vanished).
	 * The caller clears exactly these overlays — never a blanket wipe.
	 */
	newlyFixed: DiagnosticKey[]
}

export function initialDiff(): DiffState {
	return { rows: [], attemptNumber: 0 }
}

/**
 * Fold a new full-state snapshot into the running diff.
 *
 * - Rows keep first-seen order (never reordered).
 * - A key in `prev` but absent from `next` is marked `fixed: true`, kept in place.
 * - A re-appearing key un-fixes and refreshes its payload.
 * - Duplicate keys within one snapshot collapse to a single row (first wins).
 */
export function reduceDiagnostics(
	prev: DiffState,
	next: { diagnostics: Diagnostic[]; attemptNumber?: number },
): DiffResult {
	// Index incoming diagnostics by key; first occurrence wins (dedup).
	const incoming = new Map<DiagnosticKey, Diagnostic>()
	for (const d of next.diagnostics) {
		const k = keyOf(d)
		if (!incoming.has(k)) incoming.set(k, d)
	}

	const rows: DiffRow[] = []
	const seen = new Set<DiagnosticKey>()
	const newlyFixed: DiagnosticKey[] = []

	// 1. Walk existing rows in order: refresh live ones, mark vanished ones fixed.
	for (const row of prev.rows) {
		const k = keyOf(row.diagnostic)
		if (seen.has(k)) continue
		seen.add(k)
		const live = incoming.get(k)
		if (live) {
			rows.push({ diagnostic: live, fixed: false })
		} else {
			rows.push({ diagnostic: row.diagnostic, fixed: true })
			if (!row.fixed) newlyFixed.push(k)
		}
	}

	// 2. Append brand-new keys in incoming order.
	for (const d of next.diagnostics) {
		const k = keyOf(d)
		if (seen.has(k)) continue
		seen.add(k)
		rows.push({ diagnostic: d, fixed: false })
	}

	return {
		state: {
			rows,
			attemptNumber: next.attemptNumber ?? prev.attemptNumber,
		},
		newlyFixed,
	}
}
