/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { isTerminal, type RunUpdate } from "./run-update"

/**
 * Whether the EventSource should be closed after receiving `update`.
 *
 * Epic #605 replaced the old three-signal race (`BpmnResultEvent` / `BpmnRunCostEvent` /
 * `AgentProcessFinishedEvent`, which fanned out non-deterministically from one server-side
 * event and needed a settle-tracking state machine) with a single ordered `RunUpdate` stream
 * that carries exactly one terminal update. The server's own `Flux` completes right after
 * emitting it (`RunUpdateSinkRegistry.emitTerminal`), but `EventSource` reconnects by default
 * on a server-closed stream unless the client calls `close()` itself — so the caller must
 * still close explicitly on the terminal update; this is the pure predicate for when.
 */
export function shouldClose(update: RunUpdate): boolean {
	return isTerminal(update)
}
