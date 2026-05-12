import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const TARGET_TYPES = [
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ParallelGateway",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TARGET_TYPES)) {
			return
		}

		if ((node.incoming || []).length <= 1 || (node.outgoing || []).length > 1) {
			return
		}

		if (node.name && String(node.name).trim()) {
			reporter.report(node.id, "Converging gateway should remain unnamed")
		}
	}

	return { check }
}
