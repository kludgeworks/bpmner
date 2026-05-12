import assert from "node:assert/strict"
import { describe, it } from "node:test"
import BpmnModdle from "bpmn-moddle"
import { fixBpmnXml } from "../src/auto-fix/engine"
import { autoFixMetadata } from "../src/auto-fix/registry"
import { fixtures } from "./fixtures"

type Issue = { id?: string; rule: string; message?: string }

async function roundTrip(xml: string): Promise<void> {
	const moddle = new BpmnModdle()
	await moddle.fromXML(xml)
}

describe("auto-fix engine", () => {
	// ─── attribute-mutation ─────────────────────────────────────────────────────

	it("gtw-02 metadata is registered as attribute-mutation", () => {
		const m = autoFixMetadata("klm/gtw-converging-gateway-unnamed")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "attribute-mutation")
	})

	it("gtw-02: clears converging gateway name", async () => {
		const issues: Issue[] = [
			{
				id: "Gateway_1",
				rule: "bpmnlint-plugin-klm/gtw-converging-gateway-unnamed",
			},
		]
		const result = await fixBpmnXml(fixtures.gtw02Invalid, issues)
		assert.equal(result.changed, true)
		assert.equal(result.applied[0].rule, "klm/gtw-converging-gateway-unnamed")
		assert.ok(!result.xml.includes('name="Decision merged"'))
		await roundTrip(result.xml)
	})

	it("gtw-03 metadata is registered as attribute-mutation", () => {
		const m = autoFixMetadata("klm/gtw-gateway-no-work-label")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "attribute-mutation")
	})

	it("gtw-03: clears work-labelled gateway name", async () => {
		const issues: Issue[] = [
			{
				id: "Gateway_1",
				rule: "bpmnlint-plugin-klm/gtw-gateway-no-work-label",
			},
		]
		const result = await fixBpmnXml(fixtures.gtw03Invalid, issues)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('name="Check document'))
		await roundTrip(result.xml)
	})

	it("superfluous-termination: removes TerminateEventDefinition", async () => {
		const issues: Issue[] = [{ id: "End_1", rule: "superfluous-termination" }]
		const result = await fixBpmnXml(
			fixtures.superfluousTerminationFixable,
			issues,
		)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes("terminateEventDefinition"))
		await roundTrip(result.xml)
	})

	// ─── string-manipulation ────────────────────────────────────────────────────

	it("act-02 metadata is registered as string-manipulation", () => {
		const m = autoFixMetadata("klm/act-activity-label-capitalization")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("act-02: capitalizes first word and lowercases the rest", async () => {
		const issues: Issue[] = [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-klm/act-activity-label-capitalization",
			},
		]
		const result = await fixBpmnXml(fixtures.act02Invalid, issues)
		assert.equal(result.changed, true)
		assert.ok(result.xml.includes('name="Receive customer request"'))
		await roundTrip(result.xml)
	})

	it("act-02: preserves acronyms in subsequent words", async () => {
		const xml = fixtures.act02Invalid.replace(
			"receive Customer request",
			"send SLA report",
		)
		const issues: Issue[] = [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-klm/act-activity-label-capitalization",
			},
		]
		const result = await fixBpmnXml(xml, issues)
		assert.ok(result.xml.includes('name="Send SLA report"'))
	})

	it("name-02 metadata is registered as string-manipulation", () => {
		const m = autoFixMetadata("klm/name-uncommon-abbreviations")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("name-02: expands known abbreviations from replacement map", async () => {
		const issues: Issue[] = [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-klm/name-uncommon-abbreviations",
			},
		]
		const result = await fixBpmnXml(fixtures.name02FixableAbbrev, issues)
		assert.equal(result.changed, true)
		assert.ok(result.xml.includes('name="Submit request form"'))
		await roundTrip(result.xml)
	})

	it("name-03 metadata is registered as string-manipulation", () => {
		const m = autoFixMetadata("klm/name-no-element-type-words")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("name-03: strips element type words from task name", async () => {
		const issues: Issue[] = [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-klm/name-no-element-type-words",
			},
		]
		const result = await fixBpmnXml(
			fixtures.name03TypeWordsInElementName,
			issues,
		)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('name="Review event"'))
		assert.ok(result.xml.includes('name="Review"'))
		await roundTrip(result.xml)
	})

	it("data-01 metadata is registered as string-manipulation", () => {
		const m = autoFixMetadata("klm/data-no-type-words-in-data-name")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("data-01: strips type words from data element name", async () => {
		const issues: Issue[] = [
			{
				id: "Data_1",
				rule: "bpmnlint-plugin-klm/data-no-type-words-in-data-name",
			},
		]
		const result = await fixBpmnXml(fixtures.data01TypeWordsInDataName, issues)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('name="Approval process"'))
		assert.ok(result.xml.includes('name="Approval"'))
		await roundTrip(result.xml)
	})

	// ─── node-deletion ───────────────────────────────────────────────────────────

	it("no-duplicate-sequence-flows metadata is registered as node-deletion", () => {
		const m = autoFixMetadata("no-duplicate-sequence-flows")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "node-deletion")
	})

	it("no-duplicate-sequence-flows: deletes the duplicate flow", async () => {
		const issues: Issue[] = [
			{ id: "Flow_2", rule: "no-duplicate-sequence-flows" },
		]
		const result = await fixBpmnXml(fixtures.noDuplicateFlowFixable, issues)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('id="Flow_2"'))
		await roundTrip(result.xml)
	})

	it("no-duplicate-sequence-flows: skips non-flow secondary reports", async () => {
		const issues: Issue[] = [
			{ id: "Flow_2", rule: "no-duplicate-sequence-flows" },
			{ id: "Task_1", rule: "no-duplicate-sequence-flows" },
		]
		const result = await fixBpmnXml(fixtures.noDuplicateFlowFixable, issues)
		assert.equal(result.applied.length, 1)
		assert.equal(result.skipped.length, 1)
	})

	it("single-blank-start-event metadata is registered as node-deletion", () => {
		const m = autoFixMetadata("single-blank-start-event")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "node-deletion")
	})

	it("single-blank-start-event: deletes extra blank start event and its outgoing flow", async () => {
		const issues: Issue[] = [
			{ id: "Process_1", rule: "single-blank-start-event" },
		]
		const result = await fixBpmnXml(
			fixtures.singleBlankStartEventFixable,
			issues,
		)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('id="Start_2"'))
		assert.ok(!result.xml.includes('id="Flow_2"'))
		assert.ok(result.xml.includes('id="Start_1"'))
		await roundTrip(result.xml)
	})

	it("single-event-definition metadata is registered as node-deletion", () => {
		const m = autoFixMetadata("single-event-definition")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "node-deletion")
	})

	it("single-event-definition: keeps first definition, removes extra", async () => {
		const issues: Issue[] = [{ id: "End_1", rule: "single-event-definition" }]
		const result = await fixBpmnXml(
			fixtures.singleEventDefinitionFixable,
			issues,
		)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('id="ErrDef_1"'))
		assert.ok(result.xml.includes('id="MsgDef_1"'))
		await roundTrip(result.xml)
	})

	it("evt-10 metadata is registered as node-deletion", () => {
		const m = autoFixMetadata("klm/evt-start-no-incoming")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "node-deletion")
	})

	it("evt-10: deletes incoming flow from start event", async () => {
		const issues: Issue[] = [
			{
				id: "StartEvent_1",
				rule: "bpmnlint-plugin-klm/evt-start-no-incoming",
			},
		]
		const result = await fixBpmnXml(fixtures.evt10StartWithIncoming, issues)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('id="Flow_0"'))
		await roundTrip(result.xml)
	})

	// ─── ast-rewiring ─────────────────────────────────────────────────────────────

	it("superfluous-gateway metadata is registered as ast-rewiring", () => {
		const m = autoFixMetadata("superfluous-gateway")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "ast-rewiring")
	})

	it("gtw-22 metadata is registered as ast-rewiring", () => {
		const m = autoFixMetadata("klm/gtw-superfluous-gateway")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "ast-rewiring")
	})

	it("gtw-22: removes superfluous gateway and rewires flows", async () => {
		const issues: Issue[] = [
			{
				id: "Gateway_1",
				rule: "bpmnlint-plugin-klm/gtw-superfluous-gateway",
			},
		]
		const result = await fixBpmnXml(
			fixtures.gtw22SuperfluousGatewayFixable,
			issues,
		)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('id="Gateway_1"'))
		assert.ok(!result.xml.includes('id="Flow_2"'))
		assert.ok(result.xml.includes('id="Flow_1"'))
		await roundTrip(result.xml)

		const moddle = new BpmnModdle()
		const { rootElement } = await moddle.fromXML(result.xml)
		type Def = {
			rootElements?: Array<{
				flowElements?: Array<{
					id?: string
					$type?: string
					targetRef?: { id?: string }
				}>
			}>
		}
		const process = (rootElement as Def).rootElements?.[0]
		const flow1 = process?.flowElements?.find((el) => el.id === "Flow_1")
		assert.equal(flow1?.targetRef?.id, "Task_1")
	})

	it("fake-join metadata is registered as ast-rewiring", () => {
		const m = autoFixMetadata("fake-join")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "ast-rewiring")
	})

	it("gtw-21 metadata is registered as ast-rewiring", () => {
		const m = autoFixMetadata("klm/gtw-fake-join")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "ast-rewiring")
	})

	it("gtw-21: inserts converging gateway before task with multiple incoming flows", async () => {
		const issues: Issue[] = [
			{ id: "Task_1", rule: "bpmnlint-plugin-klm/gtw-fake-join" },
		]
		const result = await fixBpmnXml(fixtures.gtw21FakeJoinFixable, issues)
		assert.equal(result.changed, true)
		await roundTrip(result.xml)

		const moddle = new BpmnModdle()
		const { rootElement } = await moddle.fromXML(result.xml)
		type Def = {
			rootElements?: Array<{ flowElements?: Array<{ $type?: string }> }>
		}
		const process = (rootElement as Def).rootElements?.[0]
		const gateways = (process?.flowElements || []).filter(
			(el) => el.$type === "bpmn:ExclusiveGateway",
		)
		assert.equal(gateways.length, 1)
	})

	it("no-gateway-join-fork metadata is registered as ast-rewiring", () => {
		const m = autoFixMetadata("no-gateway-join-fork")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "ast-rewiring")
	})

	it("gtw-20 metadata is registered as ast-rewiring", () => {
		const m = autoFixMetadata("klm/gtw-no-gateway-join-fork")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "ast-rewiring")
	})

	it("gtw-20: splits join-fork gateway into two separate gateways", async () => {
		const issues: Issue[] = [
			{
				id: "Gateway_1",
				rule: "bpmnlint-plugin-klm/gtw-no-gateway-join-fork",
			},
		]
		const result = await fixBpmnXml(fixtures.gtw20JoinForkFixable, issues)
		assert.equal(result.changed, true)
		await roundTrip(result.xml)

		const moddle = new BpmnModdle()
		const { rootElement } = await moddle.fromXML(result.xml)
		type Def = {
			rootElements?: Array<{ flowElements?: Array<{ $type?: string }> }>
		}
		const process = (rootElement as Def).rootElements?.[0]
		const gateways = (process?.flowElements || []).filter(
			(el) => el.$type === "bpmn:ExclusiveGateway",
		)
		assert.equal(gateways.length, 2)
	})

	// ─── idempotence and skips ───────────────────────────────────────────────────

	it("skips non-fixable rules with a skip entry", async () => {
		const result = await fixBpmnXml(fixtures.validBaseline, [
			{ id: "Task_1", rule: "act-verb-object-name" },
		])
		assert.equal(result.changed, false)
		assert.equal(result.skipped.length, 1)
		assert.equal(result.applied.length, 0)
	})

	it("reports parse error for invalid XML", async () => {
		const result = await fixBpmnXml("<bpmn:definitions>", [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-klm/act-activity-label-capitalization",
			},
		])
		assert.equal(result.changed, false)
		assert.ok(result.errors.some((e) => e.rule === "parse-error"))
	})
})
