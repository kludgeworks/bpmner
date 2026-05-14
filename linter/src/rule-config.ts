import catalog from "./generated/linter-rules.json"

export type PklSeverity = "error" | "warning" | "info" | "off"
export type BpmnlintRuleLevel = "error" | "warn" | "off"

export type RepairKind =
	| "LOCAL_MODEL_FIX"
	| "LOCAL_XML_FIX"
	| "LLM_MODEL_PATCH"
	| "LLM_XML_REWRITE"
	| "UNFIXABLE"
export type RepairSafety = "SAFE_AUTOMATIC" | "SAFE_MANUAL" | "LLM_ONLY"

export type Repair = {
	kind: RepairKind
	safety: RepairSafety
	handler?: string | null
	replacementMap?: Record<string, string> | null
}

export type RuleConfig = {
	id: string
	aliases?: string[]
	severity: PklSeverity
	targetElements?: string[]
	errorMessages: Record<string, string | undefined>
	staticConfig?: unknown
	repair: Repair
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

for (const rule of (catalog as RuleCatalog).rules) {
	for (const alias of rule.aliases ?? []) {
		migratedRuleConfigs.set(alias, rule)
	}
}

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
