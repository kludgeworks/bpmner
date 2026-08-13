/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { JSDOM } from "jsdom"
import {
	bindDiagramExports,
	type DiagramSerializer,
	downloadDiagram,
	setDiagramExportControlsEnabled,
} from "../src/diagram-export"

function fixture(): {
	serializer: DiagramSerializer
	xml: HTMLElement
	svg: HTMLElement
	xmlCalls: Array<{ format: boolean }>
	svgCalls: number[]
} {
	const dom = new JSDOM('<a id="xml"></a><a id="svg"></a>')
	const xml = dom.window.document.querySelector("#xml")
	const svg = dom.window.document.querySelector("#svg")
	assert.ok(xml instanceof dom.window.HTMLElement)
	assert.ok(svg instanceof dom.window.HTMLElement)
	const xmlCalls: Array<{ format: boolean }> = []
	const svgCalls: number[] = []
	return {
		xml,
		svg,
		xmlCalls,
		svgCalls,
		serializer: {
			saveXML: async (options) => {
				xmlCalls.push(options)
				return { xml: "<definitions />" }
			},
			saveSVG: async () => {
				svgCalls.push(1)
				return { svg: "<svg />" }
			},
		},
	}
}

describe("diagram export", () => {
	it("serializes current XML and SVG into matching downloads", async () => {
		const { serializer, xmlCalls } = fixture()
		const dom = new JSDOM("<!doctype html><body></body>")
		const originalDocument = globalThis.document
		const originalCreateObjectURL = URL.createObjectURL
		const originalRevokeObjectURL = URL.revokeObjectURL
		const blobs: Blob[] = []
		const downloads: Array<{ href: string; download: string }> = []
		Object.assign(globalThis, { document: dom.window.document })
		URL.createObjectURL = (blob) => {
			blobs.push(blob as Blob)
			return `blob:${blobs.length}`
		}
		URL.revokeObjectURL = () => undefined
		const originalClick = dom.window.HTMLAnchorElement.prototype.click
		dom.window.HTMLAnchorElement.prototype.click = function () {
			downloads.push({ href: this.href, download: this.download })
		}

		try {
			await downloadDiagram(serializer, "xml")
			await downloadDiagram(serializer, "svg")

			assert.deepEqual(downloads, [
				{ href: "blob:1", download: "diagram.bpmn" },
				{ href: "blob:2", download: "diagram.svg" },
			])
			assert.deepEqual(
				blobs.map((blob) => blob.type),
				["application/bpmn20-xml;charset=utf-8", "image/svg+xml;charset=utf-8"],
			)
			assert.deepEqual(xmlCalls, [{ format: true }])
		} finally {
			Object.assign(globalThis, { document: originalDocument })
			URL.createObjectURL = originalCreateObjectURL
			URL.revokeObjectURL = originalRevokeObjectURL
			dom.window.HTMLAnchorElement.prototype.click = originalClick
		}
	})

	it("does not export through disabled controls", () => {
		const { serializer, xml, svg, xmlCalls, svgCalls } = fixture()
		bindDiagramExports(serializer, { xml, svg })
		setDiagramExportControlsEnabled({ xml, svg }, false)

		xml.click()
		svg.click()

		assert.deepEqual(xmlCalls, [])
		assert.deepEqual(svgCalls, [])
		assert.equal(xml.tabIndex, -1)
		assert.equal(xml.getAttribute("aria-disabled"), "true")
	})
})
