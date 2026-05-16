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

import assert from "node:assert/strict"
import { describe, it } from "node:test"
import { BpmnLinterApi } from "../src/linter-bundle"
import { fixtures } from "./fixtures"

const api = BpmnLinterApi

describe("BpmnLinterApi bundle smoke test", () => {
	it("reports start-event-required on a minimal process", async () => {
		const issues: Array<{ rule: string }> = JSON.parse(
			await api.lintXml(fixtures.gen02DuplicateDiagram, {
				extends: ["bpmnlint:recommended"],
			}),
		)

		assert.ok(issues.some((i) => i.rule === "start-event-required"))
	})

	it("does not enable plugin rules unless explicitly configured", async () => {
		const issues: Array<{ rule: string }> = JSON.parse(
			await api.lintXml(fixtures.gen02DuplicateDiagram, {
				extends: ["bpmnlint:recommended"],
			}),
		)

		assert.ok(!issues.some((i) => i.rule.includes("bpmner/")))
	})

	it("reports bpmner/gen-no-duplicate-diagrams when plugin recommended is enabled", async () => {
		const issues: Array<{ rule: string }> = JSON.parse(
			await api.lintXml(fixtures.gen02DuplicateDiagram, {
				extends: ["plugin:bpmner/recommended"],
			}),
		)

		assert.ok(issues.some((i) => i.rule === "bpmner/gen-no-duplicate-diagrams"))
	})

	it("normalizes legacy duplicate diagram config diagnostics to the canonical rule", async () => {
		const issues: Array<{ rule: string }> = JSON.parse(
			await api.lintXml(fixtures.gen02DuplicateDiagram, {
				rules: {
					"bpmner/gen-02-no-duplicate-diagrams": "error",
				},
			}),
		)

		assert.ok(issues.some((i) => i.rule === "bpmner/gen-no-duplicate-diagrams"))
		assert.ok(
			!issues.some((i) => i.rule === "bpmner/gen-02-no-duplicate-diagrams"),
		)
	})

	it("resolves active rules from extends + rules config", () => {
		const rules = api.getRules({
			extends: ["plugin:bpmner/recommended"],
			rules: {
				"bpmner/act-verb-object-name": "error",
			},
		})

		assert.equal(rules["bpmner/act-verb-object-name"], "error")
	})

	it("returns no invalid rules for valid config", () => {
		const invalidRules = api.getInvalidRules({
			extends: ["plugin:bpmner/recommended"],
			rules: {
				"bpmner/act-verb-object-name": "error",
			},
		})

		assert.deepEqual(invalidRules, [])
	})

	it("returns invalid rule ids from resolved config", () => {
		const invalidRules = api.getInvalidRules({
			rules: {
				"bpmner/unknown-rule": "error",
			},
		})

		assert.deepEqual(invalidRules, ["bpmner/unknown-rule"])
	})

	it("returns markdown docs for plugin rules", () => {
		const docs = api.getRuleDocs(["bpmner/gen-no-duplicate-diagrams"])
		const doc = docs["bpmner/gen-no-duplicate-diagrams"]
		assert.ok(doc?.includes("# gen-no-duplicate-diagrams"))
	})

	it("returns compatibility docs for legacy duplicate diagram references", () => {
		const docs = api.getRuleDocs(["bpmner/gen-02-no-duplicate-diagrams"])
		const doc = docs["bpmner/gen-02-no-duplicate-diagrams"]
		assert.ok(doc?.includes("# gen-no-duplicate-diagrams"))
		assert.ok(doc?.includes("## Compatibility"))
	})

	// gtw-converging-gateway-unnamed is now LOCAL_MODEL_FIX — repaired in Kotlin
	// via ConvergingGatewayClearNameHandler; the TS auto-fix path is intentionally
	// skipped, so we don't smoke-test it here.

	it("fixXml returns a skip entry for non-fixable rules", async () => {
		const issues = [
			{
				id: "Task_1",
				rule: "bpmner/act-verb-object-name",
				message: "invalid name",
			},
		]

		const result = JSON.parse(
			await api.fixXml(fixtures.validBaseline, JSON.stringify(issues)),
		)

		assert.equal(result.changed, false)
		assert.equal(result.applied.length, 0)
		assert.equal(result.skipped.length, 1)
		assert.equal(result.skipped[0].rule, "bpmner/act-verb-object-name")
	})

	it("fixXml returns a structured parse error for invalid XML", async () => {
		const result = JSON.parse(await api.fixXml("invalid-xml", "[]"))

		assert.equal(result.changed, false)
		assert.equal(result.errors.length, 1)
		assert.equal(result.errors[0].rule, "parse-error")
		assert.ok(result.errors[0].message.length > 0)
	})
})
