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
			'<button id="in" type="button"></button><button id="out" type="button"></button><button id="reset" type="button"></button>',
		)
		const zoomInButton = dom.window.document.querySelector("#in")
		const zoomOutButton = dom.window.document.querySelector("#out")
		const zoomResetButton = dom.window.document.querySelector("#reset")
		assert.ok(zoomInButton instanceof dom.window.HTMLButtonElement)
		assert.ok(zoomOutButton instanceof dom.window.HTMLButtonElement)
		assert.ok(zoomResetButton instanceof dom.window.HTMLButtonElement)
		const { canvas, calls } = fakeCanvas()

		bindZoomControls(canvas, zoomInButton, zoomOutButton, zoomResetButton)
		setZoomControlsEnabled(zoomInButton, zoomOutButton, zoomResetButton, false)
		zoomInButton.click()
		assert.deepEqual(calls, [])

		setZoomControlsEnabled(zoomInButton, zoomOutButton, zoomResetButton, true)
		zoomInButton.click()
		zoomOutButton.click()
		assert.equal(calls[0]?.[0], 1.2)
		assert.ok(Math.abs(Number(calls[1]?.[0]) - 1) < Number.EPSILON)
	})

	it("binds the zoom reset button to fit viewport", () => {
		const dom = new JSDOM(
			'<button id="in" type="button"></button><button id="out" type="button"></button><button id="reset" type="button"></button>',
		)
		const zoomInButton = dom.window.document.querySelector("#in")
		const zoomOutButton = dom.window.document.querySelector("#out")
		const zoomResetButton = dom.window.document.querySelector("#reset")
		assert.ok(zoomInButton instanceof dom.window.HTMLButtonElement)
		assert.ok(zoomOutButton instanceof dom.window.HTMLButtonElement)
		assert.ok(zoomResetButton instanceof dom.window.HTMLButtonElement)
		const { canvas, calls } = fakeCanvas()

		bindZoomControls(canvas, zoomInButton, zoomOutButton, zoomResetButton)
		setZoomControlsEnabled(zoomInButton, zoomOutButton, zoomResetButton, false)
		assert.ok(zoomResetButton.disabled)

		setZoomControlsEnabled(zoomInButton, zoomOutButton, zoomResetButton, true)
		assert.ok(!zoomResetButton.disabled)

		zoomResetButton.click()
		assert.deepEqual(calls, [["fit-viewport", true]])
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
