import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const TYPES_WITH_NAMES = [
	"bpmn:Task",
	"bpmn:SubProcess",
	"bpmn:CallActivity",
	"bpmn:StartEvent",
	"bpmn:IntermediateCatchEvent",
	"bpmn:IntermediateThrowEvent",
	"bpmn:EndEvent",
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ParallelGateway",
	"bpmn:ComplexGateway",
	"bpmn:DataObjectReference",
	"bpmn:DataStoreReference",
]

const TECHNICAL_TOKENS = new Set([
	"api",
	"svc",
	"tbl",
	"req",
	"resp",
	"tmp",
	"proc",
	"obj",
])

function seemsTechnical(name: string): boolean {
	if (/[_/\\]/.test(name)) {
		return true
	}

	const tokens = name.split(/\s+/)

	return tokens.some((token) => {
		const lower = token.toLowerCase()

		if (TECHNICAL_TOKENS.has(lower)) {
			return true
		}

		if (/^[A-Z]{2,}\d+$/.test(token)) {
			return true
		}

		return /[a-zA-Z]+\d+[a-zA-Z\d]*/.test(token)
	})
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TYPES_WITH_NAMES)) {
			return
		}

		const name = node.name?.trim() || ""

		if (!name) {
			return
		}

		if (seemsTechnical(name)) {
			reporter.report(
				node.id,
				"Label appears technical/cryptic; prefer business-meaningful wording",
			)
		}
	}

	return { check }
}
