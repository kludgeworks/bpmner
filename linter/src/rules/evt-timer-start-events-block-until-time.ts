/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import { getRuleMessage } from "../rule-config"
import type { ModdleElement, Reporter } from "./_helpers"

const RULE_ID = "evt-timer-start-events-block-until-time"
const TIMER_EXPRESSION_FIELDS = ["timeDate", "timeDuration", "timeCycle"]

function hasValue(value: unknown): boolean {
	if (value == null) {
		return false
	}

	if (typeof value === "string") {
		return value.trim().length > 0
	}

	return true
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:StartEvent")) {
			return
		}

		const timerDefinitions = (node.eventDefinitions || []).filter((def) =>
			is(def, "bpmn:TimerEventDefinition"),
		)

		if (timerDefinitions.length === 0) {
			return
		}

		for (const definition of timerDefinitions) {
			const expressionCount = TIMER_EXPRESSION_FIELDS.filter((field) =>
				hasValue(definition[field]),
			).length

			if (expressionCount === 0) {
				reporter.report(
					node.id,
					getRuleMessage(RULE_ID, "missingTimerExpression"),
				)
			}

			if (expressionCount > 1) {
				reporter.report(
					node.id,
					getRuleMessage(RULE_ID, "multipleTimerExpressions"),
				)
			}
		}
	}

	return { check }
}
