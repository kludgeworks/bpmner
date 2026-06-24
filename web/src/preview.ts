/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import BpmnViewer from "bpmn-js"

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
	return JSON.parse(
		requiredElement<HTMLScriptElement>("bpmn-preview-xml").textContent ?? '""',
	)
}

function showError(error: unknown): void {
	const errorElement = requiredElement<HTMLElement>("preview-error")
	errorElement.textContent =
		error instanceof Error ? error.message : String(error)
	errorElement.style.display = "block"
}

try {
	await viewer.importXML(previewXml())
	;(viewer.get("canvas") as BpmnCanvas).zoom("fit-viewport")
} catch (error) {
	showError(error)
}
