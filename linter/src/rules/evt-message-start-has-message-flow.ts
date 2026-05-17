/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import {
	getDefinitions,
	getMessageFlows,
	getPoolIdForNode,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:StartEvent")) {
			return
		}

		const hasMessageStart = (node.eventDefinitions || []).some((def) =>
			is(def, "bpmn:MessageEventDefinition"),
		)

		if (!hasMessageStart) {
			return
		}

		const definitions = getDefinitions(node)

		if (!definitions) {
			return
		}

		const targetPool = getPoolIdForNode(node, definitions)
		const incomingMessageFlow = getMessageFlows(definitions).find((flow) => {
			if (flow.targetRef !== node) {
				return false
			}

			const sourcePool = getPoolIdForNode(flow.sourceRef, definitions)
			return Boolean(sourcePool && targetPool && sourcePool !== targetPool)
		})

		if (!incomingMessageFlow) {
			reporter.report(
				node.id,
				"Message start event must have an incoming message flow from another pool",
			)
		}
	}

	return { check }
}
