/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import type { ModdleElement, Reporter } from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (node.$type === "bpmn:Definitions") {
			const diagrams = (node.diagrams || []) as unknown[]
			if (diagrams.length > 1) {
				reporter.report(
					node.id,
					`Multiple bpmndi:BPMNDiagram elements found (${diagrams.length}). Only one diagram is allowed for compatibility with viewers like bpmn-js.`,
				)
			}
		}
	}

	return { check }
}
