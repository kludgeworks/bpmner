/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import {
	type ModdleElement,
	type Reporter,
	startsWithVerbLike,
} from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:MessageFlow")) {
			return
		}

		const name = node.name?.trim()

		if (!name) {
			return
		}

		if (startsWithVerbLike(name)) {
			reporter.report(
				node.id,
				"Message flow name should describe the message, not an action",
			)
		}
	}

	return { check }
}
