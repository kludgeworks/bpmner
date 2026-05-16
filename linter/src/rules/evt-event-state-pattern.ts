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
import type { ItemToken } from "wink-nlp"
import winkNLP from "wink-nlp"
import type { ModdleElement, Reporter } from "./_helpers"

const TARGET_TYPES = [
	"bpmn:StartEvent",
	"bpmn:IntermediateCatchEvent",
	"bpmn:IntermediateThrowEvent",
	"bpmn:EndEvent",
]

const nlp = winkNLP(model)
const its = nlp.its

const STATE_WORDS = new Set([
	"received",
	"approved",
	"rejected",
	"completed",
	"confirmed",
	"sent",
	"fulfilled",
	"failed",
	"cancelled",
	"resolved",
	"closed",
])

type TokenView = {
	text: string
	pos: string
}

function tokenize(name: string): TokenView[] {
	const doc = nlp.readDoc(name)
	const tokens: TokenView[] = []

	doc.tokens().each((token: ItemToken) => {
		tokens.push({
			text: token.out().toLowerCase(),
			pos: token.out(its.pos),
		})
	})

	return tokens.filter((token) => /[a-z]/i.test(token.text))
}

function hasStatePattern(name: string): boolean {
	const tokens = tokenize(name)

	if (tokens.length < 2) {
		return false
	}

	const hasNoun = tokens.some(
		(token) => token.pos === "NOUN" || token.pos === "PROPN",
	)
	const hasState = tokens.some((token) => {
		return (
			token.pos === "ADJ" ||
			token.pos === "VERB" ||
			token.text.endsWith("ed") ||
			STATE_WORDS.has(token.text)
		)
	})

	return hasNoun && hasState
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

		if (!hasStatePattern(name)) {
			reporter.report(
				node.id,
				"Event name should follow a noun + state/result pattern (e.g. Request approved)",
			)
		}
	}

	return { check }
}
