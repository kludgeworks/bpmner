/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

const MIN_ZOOM = 0.2
const MAX_ZOOM = 4
const ZOOM_FACTOR = 1.2

export type CanvasViewport = {
	zoom: (level?: number | "fit-viewport", center?: boolean) => number
}

export function bindZoomControls(
	canvas: CanvasViewport,
	zoomInButton: HTMLButtonElement,
	zoomOutButton: HTMLButtonElement,
): void {
	zoomInButton.addEventListener("click", () => zoomBy(canvas, ZOOM_FACTOR))
	zoomOutButton.addEventListener("click", () => zoomBy(canvas, 1 / ZOOM_FACTOR))
}

export function setZoomControlsEnabled(
	zoomInButton: HTMLButtonElement,
	zoomOutButton: HTMLButtonElement,
	enabled: boolean,
): void {
	zoomInButton.disabled = !enabled
	zoomOutButton.disabled = !enabled
}

export function fitInitialViewport(
	canvas: CanvasViewport,
	stage: string,
): boolean {
	if (stage === "LAYOUT_COMPLETE") {
		canvas.zoom("fit-viewport", true)
		return true
	}
	return false
}

export function zoomBy(canvas: CanvasViewport, factor: number): void {
	const nextZoom = Math.max(
		MIN_ZOOM,
		Math.min(MAX_ZOOM, canvas.zoom() * factor),
	)
	canvas.zoom(nextZoom)
}
