/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
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
