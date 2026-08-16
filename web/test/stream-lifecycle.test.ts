/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	CLOSED,
	CONNECTING,
	classifyArtifactResponse,
	classifyStreamError,
	describeReconnect,
} from "../src/stream-lifecycle"

describe("stream lifecycle", () => {
	it("treats the end of a completed run as graceful, whatever the ready state", () => {
		// The server completes the stream immediately after the terminal update, and EventSource
		// reports that through the same handler as a drop. Reporting it is the false
		// "Connection lost." a successful run used to end with.
		assert.equal(classifyStreamError(CLOSED, true), "graceful")
		assert.equal(classifyStreamError(CONNECTING, true), "graceful")
	})

	it("separates a retryable drop from a run that is gone", () => {
		// A non-200 fails the connection permanently and leaves readyState CLOSED; a transport
		// error leaves it CONNECTING while the browser retries on its own.
		assert.equal(classifyStreamError(CONNECTING, false), "reconnecting")
		assert.equal(classifyStreamError(CLOSED, false), "gone")
	})

	it("reports a reconnect once, rather than re-printing replayed history", () => {
		assert.equal(describeReconnect(0), "Reconnected")
		assert.equal(
			describeReconnect(1),
			"Reconnected · 1 replayed update ignored",
		)
		assert.equal(
			describeReconnect(7),
			"Reconnected · 7 replayed updates ignored",
		)
	})

	it("reads the artifact endpoint's statuses", () => {
		assert.equal(classifyArtifactResponse(200), "ok")
		// 409 means the run is not terminal yet, so the fetch was early rather than wrong.
		assert.equal(classifyArtifactResponse(409), "retry")
		assert.equal(classifyArtifactResponse(404), "gone")
		assert.equal(classifyArtifactResponse(500), "unavailable")
	})
})
