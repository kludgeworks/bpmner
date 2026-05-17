/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { isAny } from "bpmnlint-utils"
import {
	type ModdleElement,
	type Reporter,
	startsWithVerbLike,
} from "./_helpers"

const TARGET_TYPES = [
	"bpmn:IntermediateCatchEvent",
	"bpmn:IntermediateThrowEvent",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TARGET_TYPES)) {
			return
		}

		const name = node.name?.trim() || ""

		if (!name) {
			return
		}

		if (startsWithVerbLike(name)) {
			reporter.report(
				node.id,
				"Intermediate event name should describe a state, not an action",
			)
		}
	}

	return { check }
}
