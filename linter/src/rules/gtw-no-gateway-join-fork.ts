/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const GATEWAY_TYPES = [
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ParallelGateway",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, GATEWAY_TYPES)) return

		const incoming = (node.incoming || []).length
		const outgoing = (node.outgoing || []).length

		if (incoming >= 2 && outgoing >= 2) {
			reporter.report(
				node.id,
				"Gateway acts as both join and fork; split into separate converging and diverging gateways",
			)
		}
	}

	return { check }
}
