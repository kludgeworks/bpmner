import { is } from "bpmnlint-utils"
import { getRuleMessage } from "../rule-config"
import {
	isWhiteBoxParticipant,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

const RULE_ID = "pool-white-box-pool-named-by-process"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:Participant") || !isWhiteBoxParticipant(node)) {
			return
		}

		const participantName = node.name?.trim()
		const processName = node.processRef?.name?.trim()

		if (participantName && processName && participantName !== processName) {
			reporter.report(node.id, getRuleMessage(RULE_ID))
		}
	}

	return { check }
}
