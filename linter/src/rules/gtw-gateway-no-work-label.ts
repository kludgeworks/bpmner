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
import type { ModdleElement, Reporter } from "./_helpers"

const TARGET_TYPES = [
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ComplexGateway",
]
const WORK_VERB_STARTERS = new Set([
	"check",
	"validate",
	"verify",
	"approve",
	"reject",
	"review",
	"assess",
	"confirm",
])

const nlp = winkNLP(model)
const its = nlp.its

function startsWithActionVerb(name: string): boolean {
	const doc = nlp.readDoc(name)
	const first = doc.tokens().itemAt(0)
	const firstWord =
		name
			.split(/\s+/)[0]
			?.toLowerCase()
			.replace(/^[^a-z]+|[^a-z]+$/g, "") || ""

	if (WORK_VERB_STARTERS.has(firstWord)) {
		return true
	}

	if (!first) {
		return false
	}

	return first.out(its.pos) === "VERB"
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TARGET_TYPES)) {
			return
		}

		if ((node.outgoing || []).length <= 1) {
			return
		}

		const name = node.name?.trim() || ""

		if (!name) {
			return
		}

		if (startsWithActionVerb(name)) {
			reporter.report(
				node.id,
				"Gateway label should describe a decision condition, not perform work",
			)
		}
	}

	return { check }
}
