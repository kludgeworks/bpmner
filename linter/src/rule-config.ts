import catalog from "./generated/linter-rules.json"

export type PklSeverity = "error" | "warning" | "info" | "off"
export type BpmnlintRuleLevel = "error" | "warn" | "off"

export type RuleConfig = {
	id: string
	severity: PklSeverity
	targetElements?: string[]
	errorMessages: Record<string, string | undefined>
	staticConfig?: unknown
	autoFixable: boolean
	fixStrategy:
		| "string-manipulation"
		| "attribute-mutation"
		| "node-deletion"
		| "ast-rewiring"
		| "LLM"
	fixMethod?: string | null
	replacementMap?: Record<string, string>
}

type RuleCatalog = {
	rules: RuleConfig[]
}

const severityToBpmnlintLevel: Record<PklSeverity, BpmnlintRuleLevel> = {
	error: "error",
	warning: "warn",
	info: "warn",
	off: "off",
}

const migratedRuleConfigs = new Map<string, RuleConfig>(
	(catalog as RuleCatalog).rules.map((rule) => [rule.id, rule]),
)

export function getRuleConfig(id: string): RuleConfig {
	const bareId = id.replace(/^(bpmner|bpmnlint-plugin-bpmner)\//, "")
	const config = migratedRuleConfigs.get(bareId)

	if (!config) {
		throw new Error(`Unknown BPMNER rule metadata: ${id}`)
	}

	return config
}

export function getRuleMessage(id: string, key = "default"): string {
	const config = getRuleConfig(id)
	const message = config.errorMessages[key] || config.errorMessages.default

	if (!message) {
		throw new Error(`Missing message "${key}" for BPMNER rule metadata: ${id}`)
	}

	return message
}

export function getStaticConfig<T>(id: string): T {
	return getRuleConfig(id).staticConfig as T
}

export function getBpmnlintLevel(id: string): BpmnlintRuleLevel {
	return severityToBpmnlintLevel[getRuleConfig(id).severity]
}
