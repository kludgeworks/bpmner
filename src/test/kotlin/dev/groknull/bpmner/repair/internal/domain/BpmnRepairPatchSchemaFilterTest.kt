/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the recursive-field-name semantics that the label-patch optimisation in
 * [dev.groknull.bpmner.repair.internal.adapter.inbound.BpmnRepairAgent.requestLlmPatch]
 * relies on. The fluent API's `withoutProperties("node", "edge")` ultimately registers a
 * `Predicate<Field>` against the victools schema generator's `forFields().withIgnoreCheck`,
 * which is invoked for every field of every class the generator visits — including the
 * nested `BpmnPatchOperation`. So although `BpmnRepairPatch` itself has no `node` or
 * `edge` properties, the filter still strips them from the nested schema, taking the
 * entire `BpmnNode` sealed hierarchy and the `BpmnEdge` inline schema with it.
 *
 * This pins:
 *  - The recursive field-name semantics — a framework change that scopes the predicate
 *    to root-only fields would break this test loudly.
 *  - The downstream effect: dropping `node` / `edge` references removes every BPMN type
 *    discriminator (`USER_TASK`, `SERVICE_TASK`, …) and every event-definition `$def`
 *    from the generated schema, which is what produces the per-call schema saving.
 */
internal class BpmnRepairPatchSchemaFilterTest {
    private val objectMapper = jacksonObjectMapper()

    private fun schemaFor(filter: (java.lang.reflect.Field) -> Boolean): String {
        val converter = FilteringJacksonOutputConverter(
            clazz = BpmnRepairPatch::class.java,
            objectMapper = objectMapper,
            fieldFilter = filter,
        )
        return converter.jsonSchema
    }

    @Test
    fun `label-only filter strips node and edge from nested BpmnPatchOperation schema`() {
        val filtered = schemaFor { it.name != "node" && it.name != "edge" }

        // These properties live on `BpmnPatchOperation`, NOT on `BpmnRepairPatch`. The
        // refactor's correctness rests on the schema generator's ignore-check being applied
        // to every field of every class it visits, not just the root class.
        assertFalse(
            filtered.contains("\"node\""),
            "label-only schema should not declare a `node` property anywhere; got:\n$filtered",
        )
        assertFalse(
            filtered.contains("\"edge\""),
            "label-only schema should not declare an `edge` property anywhere; got:\n$filtered",
        )
    }

    @Test
    fun `label-only filter eliminates BpmnNode discriminator constants and event-definition defs`() {
        val filtered = schemaFor { it.name != "node" && it.name != "edge" }

        // The 14-subtype `BpmnNode` sealed hierarchy is serialised by Jackson's `@JsonTypeInfo`
        // using discriminator constants on the `type` field. Once the `node` reference is gone,
        // none of these subtype branches should remain in the schema.
        listOf(
            "USER_TASK",
            "SERVICE_TASK",
            "MANUAL_TASK",
            "SCRIPT_TASK",
            "SEND_TASK",
            "RECEIVE_TASK",
            "BUSINESS_RULE_TASK",
            "START_EVENT",
            "END_EVENT",
            "INTERMEDIATE_CATCH_EVENT",
            "INTERMEDIATE_THROW_EVENT",
            "EXCLUSIVE_GATEWAY",
            "PARALLEL_GATEWAY",
            "INCLUSIVE_GATEWAY",
        ).forEach { discriminator ->
            assertFalse(
                filtered.contains("\"$discriminator\""),
                "label-only schema should not include the `$discriminator` BPMN type discriminator; got:\n$filtered",
            )
        }

        // The event-definition sub-schemas live in `$defs` because they're referenced by
        // multiple node subtypes. Stripping `node` makes them unreachable.
        listOf(
            "BpmnNoneEventDefinition",
            "BpmnTimerEventDefinition",
            "BpmnMessageEventDefinition",
            "BpmnSignalEventDefinition",
            "BpmnErrorEventDefinition",
            "BpmnEscalationEventDefinition",
            "BpmnTerminateEventDefinition",
        ).forEach { defName ->
            assertFalse(
                filtered.contains(defName),
                "label-only schema should not reference `$defName` after node filtering; got:\n$filtered",
            )
        }
    }

    @Test
    fun `unfiltered schema retains the BpmnNode subtype hierarchy and event-definition defs as a control`() {
        val unfiltered = schemaFor { true }

        // Sanity check that, without the filter, the schema DOES carry the structures we
        // expect the filter to strip. If this control ever passes for the filtered schema
        // and fails here, the schema shape has changed and the test needs updating.
        assertTrue(
            unfiltered.contains("\"node\""),
            "control: unfiltered schema should declare the `node` property; got:\n$unfiltered",
        )
        assertTrue(
            unfiltered.contains("\"edge\""),
            "control: unfiltered schema should declare the `edge` property; got:\n$unfiltered",
        )
        assertTrue(
            unfiltered.contains("\"USER_TASK\""),
            "control: unfiltered schema should include USER_TASK discriminator; got:\n$unfiltered",
        )
        assertTrue(
            unfiltered.contains("BpmnNoneEventDefinition"),
            "control: unfiltered schema should reference BpmnNoneEventDefinition; got:\n$unfiltered",
        )
    }

    @Test
    fun `label-only filter preserves operation, name, label and id properties`() {
        val filtered = schemaFor { it.name != "node" && it.name != "edge" }

        // SET_NODE_NAME / SET_EDGE_LABEL repair operations need these fields; they must
        // remain in the filtered schema.
        listOf("operations", "reason", "type", "nodeId", "edgeId", "name", "label").forEach { property ->
            assertTrue(
                filtered.contains("\"$property\""),
                "label-only schema should still declare `$property`; got:\n$filtered",
            )
        }

        // The repair-operation enum (the `type` field on `BpmnPatchOperation`) must survive
        // — the LLM still needs to know which operation each entry represents.
        assertTrue(
            filtered.contains("SET_NODE_NAME"),
            "label-only schema should still include SET_NODE_NAME operation enum; got:\n$filtered",
        )
        assertTrue(
            filtered.contains("SET_EDGE_LABEL"),
            "label-only schema should still include SET_EDGE_LABEL operation enum; got:\n$filtered",
        )
    }
}
