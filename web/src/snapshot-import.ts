/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Returns true if the XML string contains a BPMNDiagram element.
 * The local name "BPMNDiagram" is schema-fixed (BPMN DI schema) regardless of namespace
 * prefix — for example both "bpmndi:BPMNDiagram" and "BPMNDiagram" match. A substring
 * check is sufficient and node-testable without a DOM parser.
 */
export function hasDiagram(xml: string): boolean {
	return xml.includes("BPMNDiagram")
}

export type ImportOutcome =
	| { status: "drawn" }
	| { status: "pending"; attemptNumber?: number }

/**
 * Conditionally applies client-side auto-layout before importing XML into the viewer.
 *
 * - If the XML already carries a BPMNDiagram element it is imported as-is (server geometry).
 * - If the XML is DI-less (all intermediate snapshots), `deps.layout` is called first to
 *   generate presentation-only client-side geometry before import.
 * - On ANY throw — layouter failing on broken interim XML, or the viewer rejecting the
 *   result — the outcome is `"pending"` so the caller can keep the previous diagram and
 *   show a status message. The error is never swallowed into console.error (ARCH ADR-ss-007).
 *
 * The `deps` object is injected so the module stays pure (no bpmn-js or layout-lib imports)
 * and the runnable node test can drive it with fakes.
 */
export async function importSnapshot(
	deps: {
		layout: (xml: string) => Promise<string>
		importXML: (xml: string) => Promise<{ warnings: unknown[] }>
	},
	xml: string,
	attemptNumber?: number,
): Promise<ImportOutcome> {
	try {
		const xmlToImport = hasDiagram(xml) ? xml : await deps.layout(xml)
		await deps.importXML(xmlToImport)
		return { status: "drawn" }
	} catch {
		return { status: "pending", attemptNumber }
	}
}
