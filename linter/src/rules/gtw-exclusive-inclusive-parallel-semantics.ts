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

const RULE_ID = "gtw-exclusive-inclusive-parallel-semantics"

function hasCondition(flow: ModdleElement): boolean {
	return Boolean(flow.conditionExpression)
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:ParallelGateway")) {
			return
		}

		const incoming = node.incoming || []
		const outgoing = node.outgoing || []
		const direction = String(node.gatewayDirection || "")

		if (direction === "Diverging" && outgoing.length < 2) {
			reporter.report(
				node.id,
				getRuleMessage(RULE_ID, "parallelSplitCardinality"),
			)
		}

		if (direction === "Converging" && incoming.length < 2) {
			reporter.report(
				node.id,
				getRuleMessage(RULE_ID, "parallelJoinCardinality"),
			)
		}

		for (const flow of outgoing) {
			if (hasCondition(flow) || node.default === flow) {
				reporter.report(flow.id, getRuleMessage(RULE_ID, "parallelCondition"))
			}
		}
	}

	return { check }
}
