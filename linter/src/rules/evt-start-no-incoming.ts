/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:StartEvent")) {
			return
		}

		if ((node.incoming || []).length > 0) {
			reporter.report(
				node.id,
				"Start event must not have incoming sequence flow",
			)
		}
	}

	return { check }
}
