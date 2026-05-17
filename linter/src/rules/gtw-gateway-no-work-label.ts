/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
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
