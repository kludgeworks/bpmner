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
 * Imports server-authored XML into the viewer, never inventing client-side geometry.
 *
 * - If the XML already carries a BPMNDiagram element it is imported as-is (server geometry).
 * - If the XML is DI-less (an intermediate snapshot), the outcome is `"pending"` immediately —
 *   no client-side layout is attempted, and the caller keeps showing the previous diagram.
 * - On any throw from `deps.importXML` — the viewer rejecting the result — the outcome is also
 *   `"pending"`. The error is never swallowed into console.error.
 *
 * The `deps` object is injected so the module stays pure (no bpmn-js import) and the runnable
 * node test can drive it with fakes.
 */
export async function importSnapshot(
	deps: {
		importXML: (xml: string) => Promise<{ warnings: unknown[] }>
	},
	xml: string,
	attemptNumber?: number,
): Promise<ImportOutcome> {
	if (!hasDiagram(xml)) {
		return { status: "pending", attemptNumber }
	}
	try {
		await deps.importXML(xml)
		return { status: "drawn" }
	} catch {
		return { status: "pending", attemptNumber }
	}
}
