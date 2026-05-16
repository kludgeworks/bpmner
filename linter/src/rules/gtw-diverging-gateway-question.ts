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

const TARGET_TYPES = ["bpmn:ExclusiveGateway", "bpmn:InclusiveGateway"]
const nlp = winkNLP(model)
const its = nlp.its

const INTERROGATIVE_STARTERS = new Set([
	"is",
	"are",
	"am",
	"was",
	"were",
	"do",
	"does",
	"did",
	"can",
	"could",
	"will",
	"would",
	"shall",
	"should",
	"may",
	"might",
	"must",
	"have",
	"has",
	"had",
	"who",
	"what",
	"when",
	"where",
	"why",
	"how",
	"which",
	"whose",
	"whom",
])

function isInterrogativeName(name: string): boolean {
	if (name.endsWith("?")) {
		return true
	}

	const doc = nlp.readDoc(name)
	const firstToken = doc.tokens().itemAt(0)

	if (!firstToken) {
		return false
	}

	const firstWord = name.split(/\s+/)[0]?.toLowerCase() || ""

	if (INTERROGATIVE_STARTERS.has(firstWord)) {
		return true
	}

	const firstPos = firstToken.out(its.pos)

	// Accept aux-led formulations such as "Can passenger board".
	return firstPos === "AUX"
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

		if (!name || !isInterrogativeName(name)) {
			reporter.report(
				node.id,
				"Diverging exclusive/inclusive gateway should be named as a question",
			)
		}
	}

	return { check }
}
