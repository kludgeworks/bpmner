/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import type { RunPhase, RunUpdate } from "./run-update"
import { isTerminal } from "./run-update"

/**
 * The run as an **ordered list of phase occurrences**, not a map keyed by phase.
 *
 * Two phases repeat within a single run: `READINESS`, once per clarification round up to
 * `maxRounds`, and `VALIDATION`, once per repair-loop attempt. A phase-keyed model cannot
 * represent either, so nothing here is keyed by phase name.
 *
 * Every `RunUpdate` reports a phase that has **finished**. The reducer therefore closes the
 * occurrence the update refers to and opens the one that is now running — so the row shown as
 * active names the work actually in flight, not the work just completed.
 */

export type OccurrenceState = "pending" | "active" | "repeat" | "done"

export type Occurrence = {
	phase: RunPhase
	state: OccurrenceState
	/** Milliseconds since the run was submitted. */
	started: number
	ended?: number
	detail?: Record<string, string>
	/** Clarification round, on `AWAITING_INPUT` only. */
	round?: number
	/** Repair attempt, on a repeating `VALIDATION` only. */
	attempt?: number
	/**
	 * A question has been announced by a non-ready readiness verdict but has not arrived yet.
	 * Without this the run has nothing to show between the two updates.
	 */
	anticipating?: boolean
	/** A `READINESS` occurrence that follows a clarification answer rather than opening the run. */
	again?: boolean
}

export type RunState = {
	occurrences: Occurrence[]
	/** Highest `seq` accepted, so replayed updates after a reconnect are dropped. */
	lastSeq: number
	/** Updates dropped as already seen, reported once on reconnect rather than duplicated. */
	ignored: number
	/** True while parked on the user; the clock does not advance. */
	paused: boolean
	terminal: RunUpdate | null
}

/** Phase order, and the only phases the rail projects as pending rows. `DRAFT` never gets a row. */
export const PHASE_ORDER: RunPhase[] = [
	"READINESS",
	"CONTRACT",
	"OUTLINE",
	"VALIDATION",
	"LAYOUT",
	"ALIGNMENT",
	"FINISHED",
]

export type GroupKey = "understand" | "structure" | "refine"

/** Which group a phase belongs to. `FINISHED` is an ungrouped tail row. */
export const PHASE_GROUP: Partial<Record<RunPhase, GroupKey>> = {
	READINESS: "understand",
	AWAITING_INPUT: "understand",
	CONTRACT: "understand",
	OUTLINE: "structure",
	DRAFT: "structure",
	VALIDATION: "refine",
	LAYOUT: "refine",
	ALIGNMENT: "refine",
}

export const GROUP_LABELS: Record<
	GroupKey,
	{ title: string; subtitle: string }
> = {
	understand: {
		title: "Understand",
		subtitle: "making sense of what you wrote",
	},
	structure: {
		title: "Structure",
		subtitle: "working out how it fits together",
	},
	refine: { title: "Refine", subtitle: "making it correct and readable" },
}

/**
 * Observed durations, in seconds, across the two logged reference runs. A range is shown only
 * where both runs agree closely enough for it to be information rather than noise; `varies`
 * stages are reported as a spread and never as a point estimate, and no stage drives a
 * percentage-complete bar because the repair loop is unbounded.
 */
export const PHASE_ESTIMATE: Partial<
	Record<RunPhase, { low: number; high: number; varies?: boolean }>
> = {
	READINESS: { low: 12, high: 15 },
	CONTRACT: { low: 12, high: 31, varies: true },
	OUTLINE: { low: 25, high: 26 },
	VALIDATION: { low: 5, high: 17, varies: true },
	LAYOUT: { low: 2, high: 3 },
	ALIGNMENT: { low: 4, high: 5 },
	FINISHED: { low: 2, high: 4 },
}

export function initialRunState(): RunState {
	return {
		occurrences: [],
		lastSeq: 0,
		ignored: 0,
		paused: false,
		terminal: null,
	}
}

/** Opens the run at submit time, so the first stage is already running before any update lands. */
export function startRun(at: number): RunState {
	return { ...initialRunState(), occurrences: [occurrence("READINESS", at)] }
}

/**
 * Closes the clarification occurrence and re-opens readiness.
 *
 * Answering runs `reassess`, which publishes its own readiness assessment — so the next update
 * is another `READINESS`, never `CONTRACT`.
 */
export function answerSubmitted(state: RunState, at: number): RunState {
	const closed = closeActive(state.occurrences, at)
	return {
		...state,
		paused: false,
		occurrences: [...closed, { ...occurrence("READINESS", at), again: true }],
	}
}

/**
 * Clears a stale pause after an answer was rejected with `409` — another client's answer
 * already resumed the run. The occurrences are left as they are: the update that actually
 * closes the `AWAITING_INPUT` row is still in flight on the stream and applies normally.
 */
export function clearPause(state: RunState): RunState {
	return { ...state, paused: false }
}

/** Applies one `RunUpdate`. Idempotent per `seq`: a replayed update is counted and dropped. */
export function applyUpdate(
	state: RunState,
	update: RunUpdate,
	at: number,
): RunState {
	// `seq` is a total order assigned by a single writer per run, so a replayed update after a
	// reconnect is identified by seq alone and must not reach state or the log twice.
	if (update.seq <= state.lastSeq) {
		return { ...state, ignored: state.ignored + 1 }
	}
	const base = { ...state, lastSeq: update.seq }

	if (isTerminal(update)) {
		return {
			...base,
			terminal: update,
			paused: false,
			occurrences: closeActive(base.occurrences, at),
		}
	}

	switch (update.phase) {
		case "AWAITING_INPUT":
			return parkForAnswer(base, update, at)
		case "READINESS":
			return afterReadiness(base, update, at)
		case "VALIDATION":
			return afterValidation(base, update, at)
		case "OUTLINE":
			// OUTLINE and DRAFT are one visible step; hold the row open until DRAFT lands.
			return {
				...base,
				occurrences: attachDetail(base.occurrences, "OUTLINE", update.detail),
			}
		default:
			return advance(base, update, at)
	}
}

/** A rendered row: an occurrence already seen, or a phase still to come. */
export type DisplayRow = Occurrence & { projected?: boolean }

/** The rows to render: occurrences already seen, then the phases still to come as pending. */
export function displayRows(state: RunState): DisplayRow[] {
	const seen = state.occurrences
	if (state.terminal) return seen
	const current = seen[seen.length - 1]
	if (!current) return seen
	const next = nextPhase(current.phase)
	const from = next ? PHASE_ORDER.indexOf(next) : -1
	if (from < 0) return seen
	const projected: DisplayRow[] = PHASE_ORDER.slice(from).map((phase) => ({
		phase,
		state: "pending",
		started: 0,
		projected: true,
	}))
	return [...seen, ...projected]
}

/** Phases still to come after `phase`, in order, excluding `DRAFT` and `AWAITING_INPUT`. */
export function nextPhase(phase: RunPhase): RunPhase | null {
	if (phase === "AWAITING_INPUT") return "READINESS"
	if (phase === "DRAFT") return "VALIDATION"
	const index = PHASE_ORDER.indexOf(phase)
	if (index < 0 || index + 1 >= PHASE_ORDER.length) return null
	return PHASE_ORDER[index + 1]
}

function parkForAnswer(
	state: RunState,
	update: RunUpdate,
	at: number,
): RunState {
	const round = Number(update.detail?.round ?? "1")
	const occurrences = [...state.occurrences]
	const current = occurrences[occurrences.length - 1]
	if (
		current &&
		current.phase === "AWAITING_INPUT" &&
		current.state !== "done"
	) {
		// Fill the anticipating row in place rather than stacking a second one for the same round.
		occurrences[occurrences.length - 1] = {
			...current,
			anticipating: false,
			round,
			detail: update.detail,
		}
		return { ...state, paused: true, occurrences }
	}
	return {
		...state,
		paused: true,
		occurrences: [
			...closeActive(occurrences, at),
			{ ...occurrence("AWAITING_INPUT", at), round, detail: update.detail },
		],
	}
}

function afterReadiness(
	state: RunState,
	update: RunUpdate,
	at: number,
): RunState {
	const closed = closeActive(state.occurrences, at)
	// `detail.verdict` is the typed signal; the parenthetical in `summary` is prose and may be
	// reworded without notice. A missing key means an older server, so fall back rather than
	// silently taking the ready branch.
	const verdict = update.detail?.verdict ?? verdictFromSummary(update.summary)
	if (verdict !== null && verdict !== "READY") {
		return {
			...state,
			occurrences: [
				...closed,
				{ ...occurrence("AWAITING_INPUT", at), anticipating: true },
			],
		}
	}
	return { ...state, occurrences: [...closed, occurrence("CONTRACT", at)] }
}

function afterValidation(
	state: RunState,
	update: RunUpdate,
	at: number,
): RunState {
	if (update.artifactState !== "DIAGNOSTIC") return advance(state, update, at)
	// The repair loop repeats validation rather than advancing past it.
	const occurrences = [...state.occurrences]
	const current = occurrences[occurrences.length - 1]
	const attempt = Number(update.detail?.attemptNumber ?? "1")
	if (current && current.phase === "VALIDATION" && current.state !== "done") {
		occurrences[occurrences.length - 1] = {
			...current,
			state: "repeat",
			attempt,
			detail: update.detail,
		}
		return { ...state, occurrences }
	}
	return {
		...state,
		occurrences: [
			...closeActive(occurrences, at),
			{
				...occurrence("VALIDATION", at),
				state: "repeat",
				attempt,
				detail: update.detail,
			},
		],
	}
}

function advance(state: RunState, update: RunUpdate, at: number): RunState {
	const withDetail = attachDetail(
		state.occurrences,
		update.phase,
		update.detail,
	)
	const closed = closeActive(withDetail, at)
	const next = nextPhase(update.phase)
	if (!next) return { ...state, occurrences: closed }
	return { ...state, occurrences: [...closed, occurrence(next, at)] }
}

function occurrence(phase: RunPhase, at: number): Occurrence {
	return { phase, state: "active", started: at }
}

function closeActive(occurrences: Occurrence[], at: number): Occurrence[] {
	return occurrences.map((row, index) =>
		index === occurrences.length - 1 && row.state !== "done"
			? { ...row, state: "done" as OccurrenceState, ended: at }
			: row,
	)
}

function attachDetail(
	occurrences: Occurrence[],
	phase: RunPhase,
	detail: Record<string, string> | undefined,
): Occurrence[] {
	if (!detail) return occurrences
	const index = occurrences.length - 1
	const current = occurrences[index]
	if (!current || current.phase !== phase) return occurrences
	const next = [...occurrences]
	next[index] = { ...current, detail }
	return next
}

/** Parses the verdict out of `Assessed input readiness (ready).`, or null when absent. */
function verdictFromSummary(summary: string): string | null {
	const match = summary.match(/\(([^)]+)\)/)
	return match ? match[1].toUpperCase() : null
}

/**
 * The label a row carries while it is the active one. Present participles throughout, so the
 * text reads as work in flight rather than a noun the reader has to interpret.
 */
export function rowLabel(row: DisplayRow): string {
	switch (row.phase) {
		case "READINESS":
			return row.again
				? "Re-reading with your answer"
				: "Reading your description"
		case "AWAITING_INPUT":
			return row.anticipating
				? "Preparing a question"
				: "Waiting on your answer"
		case "CONTRACT":
			return "Identifying steps and decisions"
		case "OUTLINE":
		case "DRAFT":
			return "Working out the flow"
		case "VALIDATION":
			return row.state === "repeat" && row.attempt
				? `Repairing the diagram (attempt ${row.attempt})`
				: "Checking it's valid"
		case "LAYOUT":
			return "Positioning everything"
		case "ALIGNMENT":
			return "Comparing against your description"
		case "FINISHED":
			return "Finishing up"
	}
}

/** The `detail` values worth surfacing under a row, already worded. */
export function rowFacts(row: DisplayRow): string[] {
	const detail = row.detail
	if (!detail) return []
	const facts: string[] = []
	const add = (value: string | undefined, noun: string) => {
		if (value !== undefined) facts.push(`${value} ${noun}`)
	}
	add(detail.issueCount, "contract issues")
	add(detail.nodeCount, "activities")
	add(detail.edgeCount, "connections")
	add(detail.conformanceCorrections, "auto-corrections")
	add(detail.graphIssues, "graph issues")
	add(detail.xsdIssues, "XSD issues")
	add(detail.lintIssues, "lint issues")
	return facts
}

export type PipelineGroup = {
	key: GroupKey
	title: string
	subtitle: string
	state: OccurrenceState
	rows: DisplayRow[]
}

/**
 * Groups rows for rendering, preserving arrival order. Groups are contiguous runs of the real
 * phase order, so a repeated phase stays inside the group it belongs to. `FINISHED` has no
 * group and is returned separately as the ungrouped tail.
 */
export function groupRows(rows: DisplayRow[]): {
	groups: PipelineGroup[]
	tail: DisplayRow[]
} {
	const groups: PipelineGroup[] = []
	const tail: DisplayRow[] = []
	for (const row of rows) {
		const key = PHASE_GROUP[row.phase]
		if (!key) {
			tail.push(row)
			continue
		}
		const last = groups[groups.length - 1]
		if (last && last.key === key) {
			last.rows.push(row)
		} else {
			groups.push({ key, ...GROUP_LABELS[key], state: "pending", rows: [row] })
		}
	}
	for (const group of groups) {
		group.state = group.rows.some(
			(row) => row.state === "active" || row.state === "repeat",
		)
			? "active"
			: group.rows.every((row) => row.state === "done")
				? "done"
				: "pending"
	}
	return { groups, tail }
}

/**
 * The short form used where a full sentence has no room — the summary's timing column is 78px
 * wide, which truncates "Identifying steps and decisions" to "Identifying ste…".
 */
export function shortLabel(row: DisplayRow): string {
	switch (row.phase) {
		case "READINESS":
			return row.again ? "Re-read" : "Read"
		case "AWAITING_INPUT":
			return "Clarify"
		case "CONTRACT":
			return "Identify"
		case "OUTLINE":
		case "DRAFT":
			return "Flow"
		case "VALIDATION":
			return "Validate"
		case "LAYOUT":
			return "Position"
		case "ALIGNMENT":
			return "Compare"
		case "FINISHED":
			return "Finish"
	}
}
