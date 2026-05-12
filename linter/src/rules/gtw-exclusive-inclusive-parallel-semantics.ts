import { is } from "bpmnlint-utils"
import { getRuleMessage } from "../rule-config"
import type { ModdleElement, Reporter } from "./_helpers"

const RULE_ID = "gtw-exclusive-inclusive-parallel-semantics"

function hasCondition(flow: ModdleElement): boolean {
	return Boolean(flow.conditionExpression)
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:ParallelGateway")) {
			return
		}

		const incoming = node.incoming || []
		const outgoing = node.outgoing || []
		const direction = String(node.gatewayDirection || "")

		if (direction === "Diverging" && outgoing.length < 2) {
			reporter.report(
				node.id,
				getRuleMessage(RULE_ID, "parallelSplitCardinality"),
			)
		}

		if (direction === "Converging" && incoming.length < 2) {
			reporter.report(
				node.id,
				getRuleMessage(RULE_ID, "parallelJoinCardinality"),
			)
		}

		for (const flow of outgoing) {
			if (hasCondition(flow) || node.default === flow) {
				reporter.report(flow.id, getRuleMessage(RULE_ID, "parallelCondition"))
			}
		}
	}

	return { check }
}
