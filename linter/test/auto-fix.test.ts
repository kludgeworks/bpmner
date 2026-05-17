/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

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

	// gtw-converging-gateway-unnamed (gtw-02) is now LOCAL_MODEL_FIX — repaired
	// in Kotlin via ConvergingGatewayClearNameHandler; no TS auto-fix path.

	it("gtw-03 metadata is registered as attribute-mutation", () => {
		const m = autoFixMetadata("bpmner/gtw-gateway-no-work-label")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "attribute-mutation")
	})

	it("gtw-03: clears work-labelled gateway name", async () => {
		const issues: Issue[] = [
			{
				id: "Gateway_1",
				rule: "bpmnlint-plugin-bpmner/gtw-gateway-no-work-label",
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
		const m = autoFixMetadata("bpmner/act-activity-label-capitalization")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("act-02: capitalizes first word and lowercases the rest", async () => {
		const issues: Issue[] = [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-bpmner/act-activity-label-capitalization",
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
				rule: "bpmnlint-plugin-bpmner/act-activity-label-capitalization",
			},
		]
		const result = await fixBpmnXml(xml, issues)
		assert.ok(result.xml.includes('name="Send SLA report"'))
	})

	it("name-02 metadata is registered as string-manipulation", () => {
		const m = autoFixMetadata("bpmner/name-uncommon-abbreviations")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("name-02: expands known abbreviations from replacement map", async () => {
		const issues: Issue[] = [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-bpmner/name-uncommon-abbreviations",
			},
		]
		const result = await fixBpmnXml(fixtures.name02FixableAbbrev, issues)
		assert.equal(result.changed, true)
		assert.ok(result.xml.includes('name="Submit request form"'))
		await roundTrip(result.xml)
	})

	it("name-03 metadata is registered as string-manipulation", () => {
		const m = autoFixMetadata("bpmner/name-no-element-type-words")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("name-03: strips element type words from task name", async () => {
		const issues: Issue[] = [
			{
				id: "Task_1",
				rule: "bpmnlint-plugin-bpmner/name-no-element-type-words",
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
		const m = autoFixMetadata("bpmner/data-no-type-words-in-data-name")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "string-manipulation")
	})

	it("data-01: strips type words from data element name", async () => {
		const issues: Issue[] = [
			{
				id: "Data_1",
				rule: "bpmnlint-plugin-bpmner/data-no-type-words-in-data-name",
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
		const m = autoFixMetadata("bpmner/evt-start-no-incoming")
		assert.ok(m?.autoFixable)
		assert.equal(m?.fixStrategy, "node-deletion")
	})

	it("evt-10: deletes incoming flow from start event", async () => {
		const issues: Issue[] = [
			{
				id: "StartEvent_1",
				rule: "bpmnlint-plugin-bpmner/evt-start-no-incoming",
			},
		]
		const result = await fixBpmnXml(fixtures.evt10StartWithIncoming, issues)
		assert.equal(result.changed, true)
		assert.ok(!result.xml.includes('id="Flow_0"'))
		await roundTrip(result.xml)
	})

	// Topology rules (gtw-20 no-gateway-join-fork, gtw-21 fake-join,
	// gtw-22 superfluous-gateway) are now LOCAL_MODEL_FIX — repaired in Kotlin
	// via BpmnLocalModelFixHandler implementations; no TS auto-fix path.

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
				rule: "bpmnlint-plugin-bpmner/act-activity-label-capitalization",
			},
		])
		assert.equal(result.changed, false)
		assert.ok(result.errors.some((e) => e.rule === "parse-error"))
	})
})
