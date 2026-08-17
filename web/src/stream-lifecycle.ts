/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Reading the two failure channels a run can hit: the event stream, and the fetch for the
 * finished diagram. Both are pure, so the branch a failure takes is testable without a network.
 */

/** `EventSource.readyState`; named rather than compared as a bare number at the call site. */
export const CONNECTING = 0
export const CLOSED = 2

export type StreamError =
	/** The server completed the stream after the terminal update. Nothing to report. */
	| "graceful"
	/** Transport dropped mid-run. `EventSource` retries on its own and the server replays. */
	| "reconnecting"
	/** The run is gone: unknown or evicted id. No retry will help. */
	| "gone"

/**
 * `EventSource` reports a graceful end of stream through the same handler as a dropped
 * connection, so the terminal update is what distinguishes them — without that check a run that
 * succeeded reports a connection error.
 *
 * A non-200 response fails the connection permanently and leaves `readyState` CLOSED; a
 * transport error leaves it CONNECTING while the browser retries.
 */
export function classifyStreamError(
	readyState: number,
	sawTerminal: boolean,
): StreamError {
	if (sawTerminal) return "graceful"
	return readyState === CLOSED ? "gone" : "reconnecting"
}

/**
 * What a reconnect reports. The server replays from the first update, so the client re-reads
 * everything it has already applied; saying so once is honest, re-printing the history is not.
 */
export function describeReconnect(ignored: number): string {
	if (ignored === 0) return "Reconnected"
	return `Reconnected · ${ignored} replayed update${ignored === 1 ? "" : "s"} ignored`
}

export type ArtifactOutcome =
	| "ok"
	/** Still running: the artifact endpoint 409s until the run is terminal. */
	| "retry"
	/** Unknown or evicted run. */
	| "gone"
	| "unavailable"

/**
 * `GET /generations/{id}/bpmn` 409s while a run is not terminal. Reaching that means the client
 * fetched too early, which one retry covers without hiding it — the caller logs it either way.
 */
export function classifyArtifactResponse(status: number): ArtifactOutcome {
	if (status === 200) return "ok"
	if (status === 409) return "retry"
	if (status === 404) return "gone"
	return "unavailable"
}
