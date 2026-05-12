export type AutoFixChange = {
	rule: string
	elementId?: string
	message: string
}

export type AutoFixSkip = {
	rule: string
	elementId?: string
	message: string
}

export type AutoFixError = {
	rule: string
	elementId?: string
	message: string
}

export type AutoFixResult = {
	changed: boolean
	xml: string
	applied: AutoFixChange[]
	skipped: AutoFixSkip[]
	errors: AutoFixError[]
}

export type AutoFixRuleMetadata = {
	rule: string
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

export type AutoFixLintIssue = {
	id?: string | null
	elementId?: string | null
	node?: { id?: string | null }
	rule: string
	message?: string
	[key: string]: unknown
}

export type ModdleElement = {
	id?: string
	name?: string
	$type?: string
	get?(name: string): unknown
	set?(name: string, value: unknown): void
	[key: string]: unknown
}

export type AutoFixContext = {
	rootElement: ModdleElement
	elementsById: Map<string, ModdleElement>
	createElement: (
		type: string,
		attrs?: Record<string, unknown>,
	) => ModdleElement
	generateId: () => string
}

const KLM_PLUGIN_PREFIX = "bpmnlint-plugin-klm/"

export function normalizeRuleId(rule: string): string {
	if (rule.startsWith(KLM_PLUGIN_PREFIX)) {
		return `klm/${rule.slice(KLM_PLUGIN_PREFIX.length)}`
	}

	return rule
}

export function issueElementId(issue: AutoFixLintIssue): string | undefined {
	return issue.id || issue.elementId || issue.node?.id || undefined
}
