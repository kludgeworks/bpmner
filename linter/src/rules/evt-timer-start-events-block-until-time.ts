/**
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
