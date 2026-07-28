/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import {
	type CanvasViewport,
	fitInitialViewport,
	zoomBy,
} from "../src/canvas-viewport"

type ZoomCall = [number | "fit-viewport", boolean?]

function fakeCanvas(initialZoom = 1): {
	canvas: CanvasViewport
	calls: ZoomCall[]
} {
	const calls: ZoomCall[] = []
	let currentZoom = initialZoom
	return {
		canvas: {
			zoom: (level, center) => {
				if (level === undefined) return currentZoom
				calls.push([level, center])
				if (typeof level === "number") currentZoom = level
				return currentZoom
			},
		},
		calls,
	}
}

describe("canvas viewport", () => {
	it("centers only the authoritative initial layout", () => {
		const { canvas, calls } = fakeCanvas()

		fitInitialViewport(canvas, "INITIAL_RENDER")
		fitInitialViewport(canvas, "LAYOUT_COMPLETE")

		assert.deepEqual(calls, [["fit-viewport", true]])
	})

	it("zooms in and out by reciprocal factors", () => {
		const { canvas, calls } = fakeCanvas()

		zoomBy(canvas, 1.2)
		zoomBy(canvas, 1 / 1.2)

		assert.equal(calls[0]?.[0], 1.2)
		assert.ok(Math.abs(Number(calls[1]?.[0]) - 1) < Number.EPSILON)
	})

	it("clamps zoom to the diagram navigation range", () => {
		const minimum = fakeCanvas(0.2)
		const maximum = fakeCanvas(4)

		zoomBy(minimum.canvas, 0.5)
		zoomBy(maximum.canvas, 2)

		assert.equal(minimum.calls[0]?.[0], 0.2)
		assert.equal(maximum.calls[0]?.[0], 4)
	})
})
