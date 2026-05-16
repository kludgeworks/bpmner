/**
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
	layoutSensitive?: boolean
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
		throw new Error(`Unknown plugin rule metadata: ${id}`)
	}

	return config
}

export function getRuleMessage(id: string, key = "default"): string {
	const config = getRuleConfig(id)
	const message = config.errorMessages[key] || config.errorMessages.default

	if (!message) {
		throw new Error(`Missing message "${key}" for plugin rule metadata: ${id}`)
	}

	return message
}

export function getStaticConfig<T>(id: string): T {
	return getRuleConfig(id).staticConfig as T
}

export function getBpmnlintLevel(id: string): BpmnlintRuleLevel {
	return severityToBpmnlintLevel[getRuleConfig(id).severity]
}
