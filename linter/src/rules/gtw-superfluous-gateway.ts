import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const GATEWAY_TYPES = [
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ParallelGateway",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, GATEWAY_TYPES)) return

		const incoming = (node.incoming || []).length
		const outgoing = (node.outgoing || []).length

		if (incoming === 1 && outgoing === 1) {
			reporter.report(
				node.id,
				"Gateway has a single incoming and single outgoing flow and can be removed",
			)
		}
	}

	return { check }
}
