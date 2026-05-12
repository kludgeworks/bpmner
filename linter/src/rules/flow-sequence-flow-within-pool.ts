import { is } from "bpmnlint-utils"
import {
	getDefinitions,
	getPoolIdForNode,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:SequenceFlow")) {
			return
		}

		const definitions = getDefinitions(node)

		if (!definitions) {
			return
		}

		const sourcePool = getPoolIdForNode(
			node.sourceRef as ModdleElement | undefined,
			definitions,
		)
		const targetPool = getPoolIdForNode(
			node.targetRef as ModdleElement | undefined,
			definitions,
		)

		if (sourcePool && targetPool && sourcePool !== targetPool) {
			reporter.report(node.id, "Sequence flow must not cross pool boundaries")
		}
	}

	return { check }
}
