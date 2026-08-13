/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

export type DiagramSerializer = {
	saveXML: (options: { format: boolean }) => Promise<{ xml?: string }>
	saveSVG: () => Promise<{ svg?: string }>
}

export type DiagramExportControls = {
	xml: HTMLElement
	svg: HTMLElement
}

type ExportFormat = "xml" | "svg"

const MIME_TYPES: Record<ExportFormat, string> = {
	xml: "application/bpmn20-xml;charset=UTF-8",
	svg: "image/svg+xml;charset=UTF-8",
}

const FILENAMES: Record<ExportFormat, string> = {
	xml: "diagram.bpmn",
	svg: "diagram.svg",
}

export function bindDiagramExports(
	serializer: DiagramSerializer,
	controls: DiagramExportControls,
): void {
	bindControl(serializer, controls.xml, "xml")
	bindControl(serializer, controls.svg, "svg")
}

export function setDiagramExportControlsEnabled(
	controls: DiagramExportControls,
	enabled: boolean,
): void {
	for (const control of [controls.xml, controls.svg]) {
		control.setAttribute("aria-disabled", String(!enabled))
		control.tabIndex = enabled ? 0 : -1
	}
}

export async function downloadDiagram(
	serializer: DiagramSerializer,
	format: ExportFormat,
): Promise<void> {
	const content =
		format === "xml"
			? (await serializer.saveXML({ format: true })).xml
			: (await serializer.saveSVG()).svg
	if (!content) return

	const url = URL.createObjectURL(
		new Blob([content], { type: MIME_TYPES[format] }),
	)
	const link = document.createElement("a")
	link.href = url
	link.download = FILENAMES[format]
	link.click()
	setTimeout(() => URL.revokeObjectURL(url))
}

function bindControl(
	serializer: DiagramSerializer,
	control: HTMLElement,
	format: ExportFormat,
): void {
	control.addEventListener("click", (event) => {
		event.preventDefault()
		if (
			control.getAttribute("aria-disabled") === "true" ||
			control.dataset.exporting
		) {
			return
		}

		control.dataset.exporting = "true"
		void downloadDiagram(serializer, format).finally(() => {
			delete control.dataset.exporting
		})
	})
}
