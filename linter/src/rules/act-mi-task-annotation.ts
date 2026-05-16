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
import {
	getAssociatedAnnotationTexts,
	getDefinitions,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

const nlp = winkNLP(model)

const ITERATION_QUANTIFIERS = new Set(["each", "every", "per"])

function toNormalizedWords(text: string): string[] {
	const doc = nlp.readDoc(text)
	const words: string[] = []

	doc.tokens().each((token: ItemToken) => {
		const tokenText = token.out()

		if (tokenText) {
			words.push(String(tokenText).toLowerCase())
		}
	})

	return words
}

function hasIterationSetIntent(text: string): boolean {
	const words = toNormalizedWords(text)
	return words.some((word) => ITERATION_QUANTIFIERS.has(word))
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, ["bpmn:Task", "bpmn:SubProcess"])) {
			return
		}

		if (
			node.loopCharacteristics?.$type !==
			"bpmn:MultiInstanceLoopCharacteristics"
		) {
			return
		}

		const definitions = getDefinitions(node)

		if (!definitions) {
			return
		}

		const annotationTexts = getAssociatedAnnotationTexts(node, definitions)
		const hasIntent = annotationTexts.some((text) =>
			hasIterationSetIntent(text),
		)

		if (!hasIntent) {
			reporter.report(
				node.id,
				"Multi-instance activity is missing a linked text annotation expressing iteration-set intent",
			)
		}
	}

	return { check }
}
