/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { JSDOM } from "jsdom"
import {
	bindZoomControls,
	type CanvasViewport,
	fitInitialViewport,
	setZoomControlsEnabled,
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
	it("fits and centers the viewport on the imported diagram", () => {
		const { canvas, calls } = fakeCanvas()

		fitInitialViewport(canvas)

		assert.deepEqual(calls, [["fit-viewport", true]])
	})

	it("zooms in and out by reciprocal factors", () => {
		const { canvas, calls } = fakeCanvas()

		zoomBy(canvas, 1.2)
		zoomBy(canvas, 1 / 1.2)

		assert.equal(calls[0]?.[0], 1.2)
		assert.ok(Math.abs(Number(calls[1]?.[0]) - 1) < Number.EPSILON)
	})

	it("enables controls only after the initial fit", () => {
		const dom = new JSDOM(
			'<button id="in" type="button"></button><button id="out" type="button"></button>',
		)
		const zoomInButton = dom.window.document.querySelector("#in")
		const zoomOutButton = dom.window.document.querySelector("#out")
		assert.ok(zoomInButton instanceof dom.window.HTMLButtonElement)
		assert.ok(zoomOutButton instanceof dom.window.HTMLButtonElement)
		const { canvas, calls } = fakeCanvas()

		bindZoomControls(canvas, zoomInButton, zoomOutButton)
		setZoomControlsEnabled(zoomInButton, zoomOutButton, false)
		zoomInButton.click()
		assert.deepEqual(calls, [])

		setZoomControlsEnabled(zoomInButton, zoomOutButton, true)
		zoomInButton.click()
		zoomOutButton.click()
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
