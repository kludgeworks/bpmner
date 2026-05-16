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

const BPMNER_PLUGIN_PREFIX = "bpmnlint-plugin-bpmner/"

export function normalizeRuleId(rule: string): string {
	if (rule.startsWith(BPMNER_PLUGIN_PREFIX)) {
		return `bpmner/${rule.slice(BPMNER_PLUGIN_PREFIX.length)}`
	}

	return rule
}

export function issueElementId(issue: AutoFixLintIssue): string | undefined {
	return issue.id || issue.elementId || issue.node?.id || undefined
}
