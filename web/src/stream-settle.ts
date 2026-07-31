/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { isTerminal, type RunUpdate } from "./run-update"

/**
 * Whether the EventSource should be closed after receiving `update`.
 *
 * The server's `RunUpdate` stream carries exactly one terminal update, and its own `Flux`
 * completes right after emitting it (`RunUpdateSinkRegistry.emitTerminal`); but `EventSource`
 * reconnects by default on a server-closed stream unless the client calls `close()` itself — so
 * the caller must still close explicitly on the terminal update. This is the pure predicate for
 * when.
 */
export function shouldClose(update: RunUpdate): boolean {
	return isTerminal(update)
}
