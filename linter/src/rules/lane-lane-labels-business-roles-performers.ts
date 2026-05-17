/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import { getRuleMessage } from "../rule-config"
import type { ModdleElement, Reporter } from "./_helpers"

const RULE_ID = "lane-lane-labels-business-roles-performers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:Lane")) {
			return
		}

		if (!node.name?.trim()) {
			reporter.report(node.id, getRuleMessage(RULE_ID))
		}
	}

	return { check }
}
