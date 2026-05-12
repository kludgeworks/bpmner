import { is } from "bpmnlint-utils"
import { getRuleMessage } from "../rule-config"
import {
	getDefinitions,
	hasAnyAssociation,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

const RULE_ID = "art-text-annotation-usage"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:TextAnnotation")) {
			return
		}

		const definitions = getDefinitions(node)

		if (!definitions || !hasAnyAssociation(node, definitions)) {
			reporter.report(node.id, getRuleMessage(RULE_ID))
		}
	}

	return { check }
}
