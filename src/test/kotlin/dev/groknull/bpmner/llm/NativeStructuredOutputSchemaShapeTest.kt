/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.authoring.AuthoringTestFixtures
import dev.groknull.bpmner.authoring.BpmnRequestDraft
import dev.groknull.bpmner.contract.FlatContractTestFixtures
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.repair.RepairTestFixtures
import java.util.function.Predicate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the schema-shape precondition contract item 1 ("native structured output if
 * provider-capability-supported and schema-compatible, else fallback") relies on: Embabel's
 * `NativeStructuredOutputMode.DEFAULT` negotiation (the implicit default every bpmner role
 * gets — see `RoleLlmOptions.kt`) requires, among other things, an object-only root schema
 * with no `oneOf`/`anyOf`/`allOf` (`nativeStructuredOutputSupport.kt`,
 * `isConservativelyCompatibleWithNativeOutput()`). bpmner cannot call Embabel's private
 * compatibility check directly, so this test asserts that same root-level structural fact
 * — the one that actually discriminates bpmner's flat DTOs from `BpmnNode`'s real
 * polymorphism — against the real schema Embabel's own `FilteringJacksonOutputConverter`
 * generates: the same converter type `promptRunner.creating(...)` uses internally (confirmed
 * by `PromptSite.kt`, which mirrors the exact production call:
 * `FilteringJacksonOutputConverter(outputType, objectMapper, Predicate { true })`).
 */
internal class NativeStructuredOutputSchemaShapeTest {
    private val objectMapper = jacksonObjectMapper()

    private fun schemaOf(clazz: Class<Any>): JsonNode {
        val json = FilteringJacksonOutputConverter(clazz, objectMapper, Predicate { true }).jsonSchema
        return objectMapper.readTree(json)
    }

    private fun assertNativeOutputCompatible(
        clazz: Class<Any>,
        label: String,
    ) {
        val schema = schemaOf(clazz)
        assertNoCombinators(schema, label)
    }

    @Test
    fun `AlignmentFindings schema is native-output compatible`() {
        assertNativeOutputCompatible(
            @Suppress("UNCHECKED_CAST")
            (AlignmentFindings::class.java as Class<Any>),
            "AlignmentFindings",
        )
    }

    @Test
    fun `FlatBpmnDefinition schema is native-output compatible`() {
        assertNativeOutputCompatible(AuthoringTestFixtures.FLAT_BPMN_DEFINITION_CLASS, "FlatBpmnDefinition")
    }

    @Test
    fun `BpmnRequestDraft schema is native-output compatible`() {
        assertNativeOutputCompatible(
            @Suppress("UNCHECKED_CAST")
            (BpmnRequestDraft::class.java as Class<Any>),
            "BpmnRequestDraft",
        )
    }

    @Test
    fun `FlatProcessContract schema is native-output compatible`() {
        assertNativeOutputCompatible(FlatContractTestFixtures.FLAT_PROCESS_CONTRACT_CLASS, "FlatProcessContract")
    }

    @Test
    fun `ProcessInputAssessment schema is native-output compatible`() {
        assertNativeOutputCompatible(
            @Suppress("UNCHECKED_CAST")
            (ProcessInputAssessment::class.java as Class<Any>),
            "ProcessInputAssessment",
        )
    }

    @Test
    fun `label-only BpmnRepairPatch schema (node and edge excluded) is native-output compatible`() {
        // Mirrors BpmnLlmRepairApplier.kt's `.creating(BpmnRepairPatch::class.java)
        // .withoutProperties("node", "edge")` for the repair-label role.
        val json = FilteringJacksonOutputConverter(
            clazz = RepairTestFixtures.BPMN_REPAIR_PATCH_CLASS,
            objectMapper = objectMapper,
            fieldFilter = { field -> field.name != "node" && field.name != "edge" },
        ).jsonSchema
        val schema = objectMapper.readTree(json)
        assertNoCombinators(schema, "label-only BpmnRepairPatch")
    }

    @Test
    fun `full BpmnRepairPatch schema (node and edge included) is NOT native-output compatible`() {
        // The repair-patch/repair-rewrite/repairer roles call plain
        // `runner.createObject(messages, BpmnRepairPatch::class.java)`, including the
        // polymorphic `node: BpmnNode?` field — this is what correctly and automatically
        // triggers the fallback path, not a gap.
        val schema = schemaOf(RepairTestFixtures.BPMN_REPAIR_PATCH_CLASS)
        assertTrue(
            schema.toString().contains("\"anyOf\""),
            "full BpmnRepairPatch schema (node/edge included) is expected to contain " +
                "`anyOf` from BpmnNode's sealed hierarchy (the polymorphic-type-branching " +
                "keyword Jackson emits for a sealed interface), demonstrating the automatic " +
                "fallback trigger; got:\n$schema",
        )
    }

    /**
     * Root-scoped, matching Embabel's real (private, unreachable) compatibility check
     * (`nativeStructuredOutputSupport.kt`'s `isConservativelyCompatibleWithNativeOutput()`),
     * which never dereferences `$ref`/`$defs` — nested reusable sub-schemas (e.g. this
     * repo's shared `MultiInstanceLoopCharacteristics`/event-definition `$defs`) sit outside
     * what that check inspects, so this test does not recurse into them either.
     */
    private fun assertNoCombinators(
        schema: JsonNode,
        label: String,
    ) {
        for (combinator in listOf("oneOf", "anyOf", "allOf")) {
            assertFalse(schema.has(combinator), "$label root schema must not use `$combinator`; got:\n$schema")
        }
    }
}
