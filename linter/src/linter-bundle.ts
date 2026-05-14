import BpmnModdle from "bpmn-moddle"
import { Linter } from "bpmnlint"
import { fixBpmnXml } from "./auto-fix/engine"
import { hasHandler } from "./auto-fix/registry"
import type { AutoFixResult } from "./auto-fix/types"
import catalog from "./generated/linter-rules.json"
import {
	configs,
	customRuleAliases,
	customRuleDocs,
	resolver,
} from "./generated/static-rules"
import type {
	Repair,
	RepairKind,
	RepairSafety,
	RuleConfig,
} from "./rule-config"

type RuleLevel = "off" | "warn" | "error"

type LintConfig = {
	extends?: string | string[]
	rules?: Record<string, RuleLevel>
}

type LintReport = {
	id?: string
	elementId?: string
	node?: { id?: string }
	message: string
	category?: string
	[key: string]: unknown
}

type LintResults = Record<string, LintReport[]>

const DEFAULT_LINT_CONFIG: LintConfig = {
	extends: ["bpmnlint:recommended"],
}

function normalizeConfigInput(
	configInput?: unknown,
	includeDefaults = true,
): LintConfig {
	if (configInput == null) {
		return includeDefaults ? DEFAULT_LINT_CONFIG : {}
	}

	if (typeof configInput === "string") {
		return {
			...(includeDefaults ? DEFAULT_LINT_CONFIG : {}),
			...JSON.parse(configInput),
		} as LintConfig
	}

	if (typeof configInput === "object") {
		return {
			...(includeDefaults ? DEFAULT_LINT_CONFIG : {}),
			...(configInput as LintConfig),
		}
	}

	return includeDefaults ? DEFAULT_LINT_CONFIG : {}
}

function toArray(value?: string | string[]): string[] {
	if (!value) {
		return []
	}
	return Array.isArray(value) ? value : [value]
}

function normalizeRuleName(ruleName: string): string {
	if (ruleName.startsWith("bpmnlint-plugin-klm/")) {
		return `klm/${ruleName.slice("bpmnlint-plugin-klm/".length)}`
	}
	return ruleName
}

function canonicalRuleName(ruleName: string): string {
	const normalized = normalizeRuleName(ruleName)
	return customRuleAliases[normalized] ?? normalized
}

function configRefKey(configRef: string): string {
	if (configRef.startsWith("plugin:")) {
		return configRef
	}
	return configRef
}

function expandPluginRuleNames(
	pkg: string,
	rules: Record<string, RuleLevel>,
): Record<string, RuleLevel> {
	const prefix = pkg === "bpmnlint-plugin-klm" ? "klm" : pkg

	return Object.fromEntries(
		Object.entries(rules).map(([ruleName, level]) => [
			ruleName.includes("/")
				? normalizeRuleName(ruleName)
				: `${prefix}/${ruleName}`,
			level,
		]),
	)
}

function resolveConfigReference(configRef: string): LintConfig {
	const key = configRefKey(configRef)
	const config = configs[key as keyof typeof configs] as LintConfig | undefined

	if (config) {
		if (key.startsWith("plugin:")) {
			const pluginName = key.slice("plugin:".length).split("/")[0]
			return {
				extends: config.extends,
				rules: expandPluginRuleNames(pluginName, config.rules ?? {}),
			}
		}

		return config
	}

	const match = key.match(/^plugin:([^/]+)\/(.+)$/)
	if (match) {
		return resolver.resolveConfig(match[1], match[2]) as LintConfig
	}

	const coreMatch = key.match(/^([^:]+):(.+)$/)
	if (coreMatch) {
		return resolver.resolveConfig(coreMatch[1], coreMatch[2]) as LintConfig
	}

	throw new Error(`Unsupported lint config reference: ${configRef}`)
}

function resolveLintConfig(
	configInput?: unknown,
	includeDefaults = true,
): LintConfig {
	const normalized = normalizeConfigInput(configInput, includeDefaults)
	const mergedRules: Record<string, RuleLevel> = {}

	for (const configRef of toArray(normalized.extends)) {
		const resolved = resolveConfigReference(configRef)
		Object.assign(mergedRules, resolveLintConfig(resolved, false).rules ?? {})
	}

	Object.assign(mergedRules, normalized.rules ?? {})

	return {
		rules: mergedRules,
	}
}

function getRuleDocs(ruleNames: string[]): Record<string, string> {
	const docs: Record<string, string> = {}

	for (const ruleName of ruleNames) {
		const normalized = normalizeRuleName(ruleName)
		const doc = customRuleDocs[normalized as keyof typeof customRuleDocs]
		if (doc) {
			docs[normalized] = doc
		}
	}

	return docs
}

function splitRuleReference(ruleName: string): { pkg: string; name: string } {
	const slashIndex = ruleName.indexOf("/")

	if (slashIndex === -1) {
		return { pkg: "bpmnlint", name: ruleName }
	}

	return {
		pkg: ruleName.slice(0, slashIndex),
		name: ruleName.slice(slashIndex + 1),
	}
}

function getInvalidRules(configInput?: unknown): string[] {
	const rules = resolveLintConfig(configInput).rules ?? {}

	return Object.keys(rules)
		.filter((ruleName) => {
			const { pkg, name } = splitRuleReference(ruleName)
			return !resolver.resolveRule(pkg, name)
		})
		.sort()
}

/**
 * Lints a BPMN XML string and returns a JSON string of issues.
 * This is designed to be called from GraalJS.
 */
export async function lintXml(
	xmlString: string,
	configInput?: unknown,
): Promise<string> {
	const moddle = new BpmnModdle()
	const resolvedConfig = resolveLintConfig(configInput)

	try {
		const { rootElement } = await moddle.fromXML(xmlString)

		const linter = new Linter({
			config: resolvedConfig,
			resolver,
		})

		const result = (await linter.lint(rootElement)) as LintResults
		const issues: Record<string, unknown>[] = []

		for (const [rule, reports] of Object.entries(result || {})) {
			for (const item of reports || []) {
				issues.push({
					...item,
					id: item.id || item.elementId || item.node?.id || null,
					rule: canonicalRuleName(rule),
					message: item.message,
					category: item.category || "error",
				})
			}
		}

		return JSON.stringify(issues)
	} catch (err: unknown) {
		return JSON.stringify([
			{
				rule: "parse-error",
				message: err instanceof Error ? err.message : String(err),
				category: "error",
			},
		])
	}
}

/**
 * Applies one bounded BPMN XML auto-fix pass and returns a JSON string result.
 * This is designed to be called from GraalJS.
 */
export async function fixXml(
	xmlString: string,
	issuesInput?: unknown,
	configInput?: unknown,
	_optionsInput?: unknown,
): Promise<string> {
	try {
		const result = await fixBpmnXml(xmlString, issuesInput, configInput, {
			lintXml,
		})
		return JSON.stringify(result)
	} catch (err: unknown) {
		const result: AutoFixResult = {
			changed: false,
			xml: xmlString,
			applied: [],
			skipped: [],
			errors: [
				{
					rule: "auto-fix-error",
					message: err instanceof Error ? err.message : String(err),
				},
			],
		}
		return JSON.stringify(result)
	}
}

type RuleCapability = {
	id: string
	severity: string
	kind: RepairKind
	safety: RepairSafety
	handlerName: string | null
	handlerExists: boolean
	replacementMap: Record<string, string> | null
}

type RuleCatalog = { rules: RuleConfig[] }

export function getRuleCapabilities(): RuleCapability[] {
	const capabilities: RuleCapability[] = []
	const errors: string[] = []

	for (const rule of (catalog as unknown as RuleCatalog).rules) {
		const repair = rule.repair as Repair
		const { kind, safety } = repair
		const handlerName = repair.handler ?? null
		const replacementMap = repair.replacementMap ?? null

		const isLocal = kind === "LOCAL_XML_FIX" || kind === "LOCAL_MODEL_FIX"
		const isLocalXml = kind === "LOCAL_XML_FIX"
		const handlerExists = handlerName !== null && hasHandler(handlerName)

		if (isLocal && !handlerName) {
			errors.push(
				`Rule ${rule.id}: ${kind} requires a handler but none declared`,
			)
		} else if (isLocalXml && handlerName && !handlerExists) {
			errors.push(
				`Rule ${rule.id}: declared handler "${handlerName}" is not registered in the TS auto-fix registry`,
			)
		} else if (!isLocal && handlerName) {
			errors.push(
				`Rule ${rule.id}: ${kind} must not declare a handler (got "${handlerName}")`,
			)
		}

		capabilities.push({
			id: rule.id,
			severity: rule.severity,
			kind,
			safety,
			handlerName,
			handlerExists,
			replacementMap,
		})
	}

	if (errors.length > 0) {
		throw new Error(`Rule capability validation failed:\n${errors.join("\n")}`)
	}

	return capabilities
}

// Expose the API to the global scope for GraalJS/Polyglot
export const BpmnLinterApi = {
	lintXml,
	fixXml,
	getDefaultConfig: () => DEFAULT_LINT_CONFIG,
	getRules: (configInput?: unknown) =>
		resolveLintConfig(configInput).rules ?? {},
	getInvalidRules,
	getRuleDocs: (ruleNamesInput?: unknown) => {
		if (typeof ruleNamesInput === "string") {
			return getRuleDocs(JSON.parse(ruleNamesInput) as string[])
		}

		if (Array.isArray(ruleNamesInput)) {
			return getRuleDocs(ruleNamesInput as string[])
		}

		return {}
	},
	getRuleCapabilities,
}

;(globalThis as typeof globalThis & { BpmnLinterApi?: unknown }).BpmnLinterApi =
	BpmnLinterApi
