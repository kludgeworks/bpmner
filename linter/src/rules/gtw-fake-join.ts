import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const TASK_TYPES = [
	"bpmn:Task",
	"bpmn:UserTask",
	"bpmn:ServiceTask",
	"bpmn:SendTask",
	"bpmn:ReceiveTask",
	"bpmn:ManualTask",
	"bpmn:BusinessRuleTask",
	"bpmn:ScriptTask",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TASK_TYPES)) return

		if ((node.incoming || []).length >= 2) {
			reporter.report(
				node.id,
				"Task has multiple incoming flows without an explicit converging gateway",
			)
		}
	}

	return { check }
}
