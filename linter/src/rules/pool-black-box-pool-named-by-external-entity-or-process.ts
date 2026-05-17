/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import { getRuleMessage } from "../rule-config"
import {
	isBlackBoxParticipant,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

const RULE_ID = "pool-black-box-pool-named-by-external-entity-or-process"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:Participant") || !isBlackBoxParticipant(node)) {
			return
		}

		if (!node.name?.trim()) {
			reporter.report(node.id, getRuleMessage(RULE_ID))
		}
	}

	return { check }
}
