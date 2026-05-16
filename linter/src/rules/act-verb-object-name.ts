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
import model from "wink-eng-lite-web-model"
import winkNLP from "wink-nlp"
import { getRuleMessage } from "../rule-config"
import type { ModdleElement, Reporter } from "./_helpers"

const RULE_ID = "act-verb-object-name"
const TARGET_TYPES = ["bpmn:Task", "bpmn:SubProcess", "bpmn:CallActivity"]
const TOO_SHORT_MESSAGE = getRuleMessage(RULE_ID, "tooShort")
const MISSING_VERB_MESSAGE = getRuleMessage(RULE_ID, "missingVerb")

const nlp = winkNLP(model)
const its = nlp.its

function isVerbLike(token: string): boolean {
	const doc = nlp.readDoc(token)
	const first = doc.tokens().itemAt(0)

	if (!first) {
		return false
	}

	const pos = first.out(its.pos)
	return pos === "VERB" || pos === "AUX"
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TARGET_TYPES)) {
			return
		}

		const rawName = node.name?.trim()

		if (!rawName) {
			return
		}

		const parts = rawName.split(/\s+/)

		if (parts.length < 2) {
			reporter.report(node.id, TOO_SHORT_MESSAGE)
			return
		}

		if (isVerbLike(parts[0])) {
			return
		}

		reporter.report(node.id, MISSING_VERB_MESSAGE)
	}

	return { check }
}
