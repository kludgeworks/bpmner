/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import {
	getDefinitions,
	getFlowPools,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:SequenceFlow")) {
			return
		}

		const definitions = getDefinitions(node)

		if (!definitions) {
			return
		}

		const { sourcePool, targetPool } = getFlowPools(node, definitions)

		if (sourcePool && targetPool && sourcePool !== targetPool) {
			reporter.report(node.id, "Sequence flow must not cross pool boundaries")
		}
	}

	return { check }
}
