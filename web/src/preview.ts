/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import BpmnViewer from "bpmn-js"
import { formatError, parsePreviewXml } from "./preview-helpers"

type BpmnCanvas = {
	zoom: (mode: "fit-viewport") => void
}

const viewer = new BpmnViewer({
	container: "#canvas",
})

function requiredElement<T extends HTMLElement>(id: string): T {
	const element = document.getElementById(id)
	if (!(element instanceof HTMLElement)) {
		throw new Error(`Missing required element #${id}`)
	}
	return element as T
}

function previewXml(): string {
	return parsePreviewXml(
		requiredElement<HTMLScriptElement>("bpmn-preview-xml").textContent,
	)
}

function showError(error: unknown): void {
	const errorElement = requiredElement<HTMLElement>("preview-error")
	errorElement.textContent = formatError(error)
	errorElement.style.display = "block"
}

try {
	await viewer.importXML(previewXml())
	const canvas = viewer.get("canvas") as BpmnCanvas
	const fitViewport = (): void => canvas.zoom("fit-viewport")
	// Fit once layout has settled (the container may still be sizing on first paint),
	// and re-fit whenever the window is resized so the whole diagram stays visible.
	requestAnimationFrame(fitViewport)
	window.addEventListener("resize", fitViewport)
} catch (error) {
	showError(error)
}
