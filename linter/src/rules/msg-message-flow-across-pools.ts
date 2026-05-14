import { is } from "bpmnlint-utils"
import {
	getDefinitions,
	getFlowPools,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:MessageFlow")) {
			return
		}

		const definitions = getDefinitions(node)

		if (!definitions) {
			return
		}

		const { sourcePool, targetPool } = getFlowPools(node, definitions)

		if (!sourcePool || !targetPool || sourcePool === targetPool) {
			reporter.report(
				node.id,
				"Message flow must connect elements in different pools",
			)
		}
	}

	return { check }
}
