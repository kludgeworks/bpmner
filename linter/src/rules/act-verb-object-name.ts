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
