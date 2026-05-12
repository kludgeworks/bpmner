import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (
			!isAny(node, [
				"bpmn:ExclusiveGateway",
				"bpmn:InclusiveGateway",
				"bpmn:ComplexGateway",
			])
		) {
			return
		}

		if ((node.outgoing || []).length <= 1) {
			return
		}

		for (const flow of node.outgoing || []) {
			if (!flow.name || !String(flow.name).trim()) {
				reporter.report(
					flow.id,
					"Sequence flow from diverging gateway must have an outcome label",
				)
			}
		}
	}

	return { check }
}
