import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const DISCOURAGED_TYPES = [
	"bpmn:Choreography",
	"bpmn:ChoreographyTask",
	"bpmn:SubChoreography",
	"bpmn:CallChoreography",
	"bpmn:Conversation",
	"bpmn:ConversationLink",
	"bpmn:ConversationAssociation",
	"bpmn:Transaction",
	"bpmn:CompensateEventDefinition",
	"bpmn:EscalationEventDefinition",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, DISCOURAGED_TYPES)) {
			return
		}

		reporter.report(
			node.id,
			`Element type <${node.$type}> is outside the supported BPMN subset`,
		)
	}

	return { check }
}
