/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { isAny } from "bpmnlint-utils"
import model from "wink-eng-lite-web-model"
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

function startsWithActionVerb(name: string): boolean {
	const doc = nlp.readDoc(name)
	const first = doc.tokens().itemAt(0)

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

		const name = node.name?.trim() || ""

		if (!name) {
			return
		}

		if (startsWithActionVerb(name)) {
			reporter.report(
				node.id,
				"Event name should describe a state/happening, not an action",
			)
		}
	}

	return { check }
}
