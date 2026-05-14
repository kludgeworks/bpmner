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

	it("does not enable KLM rules unless explicitly configured", async () => {
		const issues: Array<{ rule: string }> = JSON.parse(
			await api.lintXml(fixtures.gen02DuplicateDiagram, {
				extends: ["bpmnlint:recommended"],
			}),
		)

		assert.ok(!issues.some((i) => i.rule.includes("klm/")))
	})

	it("reports klm/gen-no-duplicate-diagrams when plugin recommended is enabled", async () => {
		const issues: Array<{ rule: string }> = JSON.parse(
			await api.lintXml(fixtures.gen02DuplicateDiagram, {
				extends: ["plugin:klm/recommended"],
			}),
		)

		assert.ok(issues.some((i) => i.rule === "klm/gen-no-duplicate-diagrams"))
	})

	it("normalizes legacy duplicate diagram config diagnostics to the canonical rule", async () => {
		const issues: Array<{ rule: string }> = JSON.parse(
			await api.lintXml(fixtures.gen02DuplicateDiagram, {
				rules: {
					"klm/gen-02-no-duplicate-diagrams": "error",
				},
			}),
		)

		assert.ok(issues.some((i) => i.rule === "klm/gen-no-duplicate-diagrams"))
		assert.ok(
			!issues.some((i) => i.rule === "klm/gen-02-no-duplicate-diagrams"),
		)
	})

	it("resolves active rules from extends + rules config", () => {
		const rules = api.getRules({
			extends: ["plugin:klm/recommended"],
			rules: {
				"klm/act-verb-object-name": "error",
			},
		})

		assert.equal(rules["klm/act-verb-object-name"], "error")
	})

	it("returns no invalid rules for valid config", () => {
		const invalidRules = api.getInvalidRules({
			extends: ["plugin:klm/recommended"],
			rules: {
				"klm/act-verb-object-name": "error",
			},
		})

		assert.deepEqual(invalidRules, [])
	})

	it("returns invalid rule ids from resolved config", () => {
		const invalidRules = api.getInvalidRules({
			rules: {
				"klm/unknown-rule": "error",
			},
		})

		assert.deepEqual(invalidRules, ["klm/unknown-rule"])
	})

	it("returns markdown docs for KLM rules", () => {
		const docs = api.getRuleDocs(["klm/gen-no-duplicate-diagrams"])
		const doc = docs["klm/gen-no-duplicate-diagrams"]
		assert.ok(doc?.includes("# gen-no-duplicate-diagrams"))
	})

	it("returns compatibility docs for legacy duplicate diagram references", () => {
		const docs = api.getRuleDocs(["klm/gen-02-no-duplicate-diagrams"])
		const doc = docs["klm/gen-02-no-duplicate-diagrams"]
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
				rule: "klm/act-verb-object-name",
				message: "invalid name",
			},
		]

		const result = JSON.parse(
			await api.fixXml(fixtures.validBaseline, JSON.stringify(issues)),
		)

		assert.equal(result.changed, false)
		assert.equal(result.applied.length, 0)
		assert.equal(result.skipped.length, 1)
		assert.equal(result.skipped[0].rule, "klm/act-verb-object-name")
	})

	it("fixXml returns a structured parse error for invalid XML", async () => {
		const result = JSON.parse(await api.fixXml("invalid-xml", "[]"))

		assert.equal(result.changed, false)
		assert.equal(result.errors.length, 1)
		assert.equal(result.errors[0].rule, "parse-error")
		assert.ok(result.errors[0].message.length > 0)
	})
})
