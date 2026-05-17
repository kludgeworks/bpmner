/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import catalog from "./generated/linter-rules.json"

type RuleConfig = {
	id: string
	aliases?: string[]
	severity: string
	hasTsImplementation: boolean
}

type RuleCatalog = {
	rules: RuleConfig[]
}

const tsRules = (catalog as RuleCatalog).rules.filter(
	(r) => r.hasTsImplementation,
)

const recommendedPluginRules = Object.fromEntries(
	tsRules.map((r) => [r.id, r.severity === "error" ? "error" : "warn"]),
) as Record<string, string>

const allPluginRules = Object.fromEntries(
	tsRules.map((r) => [r.id, "error"]),
) as Record<string, string>

const pluginRulePaths = Object.fromEntries(
	tsRules.flatMap((r) => [
		[r.id, `./rules/${r.id}`],
		...(r.aliases ?? []).map((alias) => [alias, `./rules/${r.id}`]),
	]),
) as Record<string, string>

const plugin = {
	configs: {
		recommended: {
			extends: "bpmnlint:recommended",
			rules: recommendedPluginRules,
		},
		"recommended-error": {
			extends: "bpmnlint:recommended-error",
			rules: recommendedPluginRules,
		},
		all: {
			rules: allPluginRules,
		},
	},
	rules: pluginRulePaths,
}

export = plugin
