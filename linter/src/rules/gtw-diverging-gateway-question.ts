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
