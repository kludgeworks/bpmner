/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const TARGET_TYPES = [
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ComplexGateway",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TARGET_TYPES)) {
			return
		}

		if ((node.outgoing || []).length <= 1) {
			return
		}

		for (const flow of node.outgoing || []) {
			if (!flow.name || !String(flow.name).trim()) {
				reporter.report(
					flow.id,
					"Sequence flow from diverging gateway should use an outcome condition label",
				)
			}
		}
	}

	return { check }
}
