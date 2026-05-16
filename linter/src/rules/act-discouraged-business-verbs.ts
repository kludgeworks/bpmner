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

import { isAny } from "bpmnlint-utils"
import { getRuleMessage, getStaticConfig } from "../rule-config"
import type { ModdleElement, Reporter } from "./_helpers"

const RULE_ID = "act-discouraged-business-verbs"
const TARGET_TYPES = ["bpmn:Task", "bpmn:SubProcess", "bpmn:CallActivity"]
const { discouragedLeadingVerbs } = getStaticConfig<{
	discouragedLeadingVerbs: string[]
}>(RULE_ID)
const DISCOURAGED_LEADING_VERBS = new Set(discouragedLeadingVerbs)
const MESSAGE = getRuleMessage(RULE_ID)

function firstWord(name: string): string {
	const word = name.split(/\s+/)[0] || ""
	return word.toLowerCase().replace(/^[^a-z]+|[^a-z]+$/g, "")
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TARGET_TYPES)) {
			return
		}

		const name = node.name?.trim() || ""

		if (!name) {
			return
		}

		if (DISCOURAGED_LEADING_VERBS.has(firstWord(name))) {
			reporter.report(node.id, MESSAGE)
		}
	}

	return { check }
}
