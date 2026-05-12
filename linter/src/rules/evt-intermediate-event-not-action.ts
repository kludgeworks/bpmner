import { isAny } from "bpmnlint-utils"
import model from "wink-eng-lite-web-model"
import winkNLP from "wink-nlp"
import type { ModdleElement, Reporter } from "./_helpers"

const TARGET_TYPES = [
	"bpmn:IntermediateCatchEvent",
	"bpmn:IntermediateThrowEvent",
]
const nlp = winkNLP(model)
const its = nlp.its

function startsWithVerbLike(name: string): boolean {
	const doc = nlp.readDoc(name)
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

		const name = node.name?.trim() || ""

		if (!name) {
			return
		}

		if (startsWithVerbLike(name)) {
			reporter.report(
				node.id,
				"Intermediate event name should describe a state, not an action",
			)
		}
	}

	return { check }
}
