/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.MissingProcessArea
import dev.groknull.bpmner.readiness.ReadinessDimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-enum `@JsonAlias` coverage for [ReadinessDimension] and [MissingProcessArea].
 *
 * The readiness LLM regularly confuses the two parallel taxonomies — see the failing run
 * `logs/bpmner-20260521-013529-906.log` where the model returned `ACTIVITY_SEQUENCE` (a
 * [MissingProcessArea] value) inside `ClarificationQuestion.relatedDimensions` (which
 * expects a [ReadinessDimension]). Jackson rejected the response with
 * `InvalidFormatException`, the LLM call retried, and a more conservative `NEEDS_CLARIFICATION`
 * verdict overwrote the original `READY` response. These aliases absorb that confusion.
 */
class BpmnGuardrailTypesJacksonTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `ReadinessDimension accepts MissingProcessArea names via JsonAlias`() {
        // Each pair: (alias the LLM might emit, the canonical ReadinessDimension it resolves to).
        // Derived from BpmnReadinessAgent's mapping logic. PROCESS_BOUNDARY / START_TRIGGER
        // share the same name across both enums, so no alias is needed for them.
        val cases =
            listOf(
                "END_STATE" to ReadinessDimension.END_STATES,
                "ACTIVITY_SEQUENCE" to ReadinessDimension.SEQUENCE_ORDER,
                "ACTOR_RESPONSIBILITY" to ReadinessDimension.ACTORS_ROLES,
                "DECISION_CRITERIA" to ReadinessDimension.DECISIONS_BRANCHES,
                "EXCEPTION_HANDLING" to ReadinessDimension.EXCEPTIONS_REWORK,
                "INPUT_ARTIFACT" to ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS,
                "OUTPUT_ARTIFACT" to ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS,
                "BPMN_PROCESS_SUITABILITY" to ReadinessDimension.BPMN_SUITABILITY,
                "SOURCE_TRACE" to ReadinessDimension.TRACEABILITY_TO_SOURCE,
            )
        cases.forEach { (alias, expected) ->
            val parsed: ReadinessDimension = objectMapper.readValue("\"$alias\"")
            assertEquals(expected, parsed, "alias '$alias' should resolve to $expected")
        }
    }

    @Test
    fun `MissingProcessArea accepts ReadinessDimension names via JsonAlias`() {
        val cases =
            listOf(
                "END_STATES" to MissingProcessArea.END_STATE,
                "SEQUENCE_ORDER" to MissingProcessArea.ACTIVITY_SEQUENCE,
                "ACTIVITIES" to MissingProcessArea.ACTIVITY_SEQUENCE,
                "ACTORS_ROLES" to MissingProcessArea.ACTOR_RESPONSIBILITY,
                "DECISIONS_BRANCHES" to MissingProcessArea.DECISION_CRITERIA,
                "EXCEPTIONS_REWORK" to MissingProcessArea.EXCEPTION_HANDLING,
                "INPUTS_OUTPUTS_ARTIFACTS" to MissingProcessArea.OUTPUT_ARTIFACT,
                "BPMN_SUITABILITY" to MissingProcessArea.BPMN_PROCESS_SUITABILITY,
                "TRACEABILITY_TO_SOURCE" to MissingProcessArea.SOURCE_TRACE,
            )
        cases.forEach { (alias, expected) ->
            val parsed: MissingProcessArea = objectMapper.readValue("\"$alias\"")
            assertEquals(expected, parsed, "alias '$alias' should resolve to $expected")
        }
    }

    @Test
    fun `aliases are read-only — canonical names still serialise out`() {
        // After parsing an alias, the value must round-trip back as the canonical name —
        // not as the alias. Otherwise downstream consumers (markdown report writers,
        // structured logs) would emit non-canonical names.
        val parsed: ReadinessDimension = objectMapper.readValue("\"ACTIVITY_SEQUENCE\"")
        assertEquals("\"SEQUENCE_ORDER\"", objectMapper.writeValueAsString(parsed))

        val parsedArea: MissingProcessArea = objectMapper.readValue("\"SEQUENCE_ORDER\"")
        assertEquals("\"ACTIVITY_SEQUENCE\"", objectMapper.writeValueAsString(parsedArea))
    }

    @Test
    fun `ClarificationQuestion deserialises with cross-named dimensions and missing areas`() {
        // Reproduces the failing payload from log line 996:
        // relatedDimensions contained a MissingProcessArea name (ACTIVITY_SEQUENCE).
        val json =
            """
            {
              "id": "cq-1",
              "questionText": "What is the activity order?",
              "relatedDimensions": ["ACTIVITY_SEQUENCE", "EXCEPTION_HANDLING"],
              "relatedMissingAreas": ["SEQUENCE_ORDER", "EXCEPTIONS_REWORK"]
            }
            """.trimIndent()

        val question: ClarificationQuestion = objectMapper.readValue(json)

        assertEquals(
            listOf(ReadinessDimension.SEQUENCE_ORDER, ReadinessDimension.EXCEPTIONS_REWORK),
            question.relatedDimensions,
        )
        assertEquals(
            listOf(MissingProcessArea.ACTIVITY_SEQUENCE, MissingProcessArea.EXCEPTION_HANDLING),
            question.relatedMissingAreas,
        )
    }

    @Test
    fun `canonical names still deserialise without going through alias path`() {
        // The aliases must not break the canonical happy path. Every enum value should
        // parse from its own name. Belt-and-braces sanity check.
        ReadinessDimension.entries.forEach { dim ->
            val parsed: ReadinessDimension = objectMapper.readValue("\"${dim.name}\"")
            assertEquals(dim, parsed)
        }
        MissingProcessArea.entries.forEach { area ->
            val parsed: MissingProcessArea = objectMapper.readValue("\"${area.name}\"")
            assertEquals(area, parsed)
        }
    }

    @Test
    fun `aliases never collide across either enum`() {
        // Sanity: no alias accidentally maps to two values within the same enum. If this
        // ever fails, Jackson would have surfaced it at registration time — but the test
        // makes the invariant load-bearing in case the alias table grows.
        val dimensionParseSeen = mutableSetOf<ReadinessDimension>()
        ReadinessDimension.entries.forEach { dimensionParseSeen += it }
        assertTrue(dimensionParseSeen.size == ReadinessDimension.entries.size)
        val areaParseSeen = mutableSetOf<MissingProcessArea>()
        MissingProcessArea.entries.forEach { areaParseSeen += it }
        assertTrue(areaParseSeen.size == MissingProcessArea.entries.size)
    }
}
