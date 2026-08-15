/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import BpmnModeler from "bpmn-js/lib/Modeler"
import {
	bindZoomControls,
	type CanvasViewport,
	fitInitialViewport,
	setZoomControlsEnabled,
} from "./canvas-viewport"
import {
	bindDiagramExports,
	type DiagramExportControls,
	setDiagramExportControlsEnabled,
} from "./diagram-export"
import { formatError, parsePreviewXml } from "./preview-helpers"

const modeler = new BpmnModeler({
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

const canvasEl = requiredElement<HTMLElement>("canvas")
const zoomInBtn = requiredElement<HTMLButtonElement>("zoom-in-btn")
const zoomOutBtn = requiredElement<HTMLButtonElement>("zoom-out-btn")
const zoomResetBtn = requiredElement<HTMLButtonElement>("zoom-reset-btn")
const exportControls: DiagramExportControls = {
	xml: requiredElement<HTMLElement>("download-diagram-btn"),
	svg: requiredElement<HTMLElement>("download-svg-btn"),
}

function setCanvasLoaded(loaded: boolean): void {
	canvasEl.inert = !loaded
	canvasEl.classList.toggle("canvas--disabled", !loaded)
	setZoomControlsEnabled(zoomInBtn, zoomOutBtn, zoomResetBtn, loaded)
	setDiagramExportControlsEnabled(exportControls, loaded)
}

setCanvasLoaded(false)
bindDiagramExports(modeler, exportControls)

try {
	await modeler.importXML(previewXml())
	const canvasViewport = modeler.get("canvas") as CanvasViewport
	const fitViewport = (): void => {
		fitInitialViewport(canvasViewport)
	}
	requestAnimationFrame(fitViewport)
	window.addEventListener("resize", fitViewport)

	bindZoomControls(canvasViewport, zoomInBtn, zoomOutBtn, zoomResetBtn)
	requestAnimationFrame(() => setCanvasLoaded(true))
} catch (error) {
	setCanvasLoaded(false)
	showError(error)
}
