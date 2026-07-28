/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

const MIN_ZOOM = 0.2
const MAX_ZOOM = 4

export type CanvasViewport = {
	zoom: (level?: number | "fit-viewport", center?: boolean) => number
}

export function fitInitialViewport(
	canvas: CanvasViewport,
	stage: string,
): void {
	if (stage === "LAYOUT_COMPLETE") {
		canvas.zoom("fit-viewport", true)
	}
}

export function zoomBy(canvas: CanvasViewport, factor: number): void {
	const nextZoom = Math.max(
		MIN_ZOOM,
		Math.min(MAX_ZOOM, canvas.zoom() * factor),
	)
	canvas.zoom(nextZoom)
}
