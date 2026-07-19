/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.EvidenceSourceType
import dev.groknull.bpmner.readiness.ReadinessDimension
import dev.groknull.bpmner.readiness.SourceEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@JsonAlias` coverage for [ReadinessDimension], the single enum now used for both readiness
 * dimensions and missing/gap areas (#611 deleted the parallel `MissingProcessArea` type).
 *
 * The readiness LLM regularly emits a plausible synonym for a dimension name — see the failing
 * run `logs/bpmner-20260521-013529-906.log` where the model returned `"START_STATES"` and
 * `"ACTORS_RESPONSIBILITY"`, and omitted `SourceEvidence.sourceType` entirely. Jackson rejected
 * the response with `InvalidFormatException`, the LLM call retried, and a more conservative
 * verdict overwrote the original `READY` response. These aliases, plus a safely-defaulted
 * `sourceType`, absorb that failure signature.
 */
class BpmnGuardrailTypesJacksonTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `ReadinessDimension accepts every surviving alias via JsonAlias`() {
        val cases =
            listOf(
                "START_STATES" to ReadinessDimension.START_TRIGGER,
                "END_STATE" to ReadinessDimension.END_STATES,
                "ACTIVITY_SEQUENCE" to ReadinessDimension.SEQUENCE_ORDER,
                "ACTOR_RESPONSIBILITY" to ReadinessDimension.ACTORS_ROLES,
                "ACTORS_RESPONSIBILITY" to ReadinessDimension.ACTORS_ROLES,
                "DECISION_CRITERIA" to ReadinessDimension.DECISIONS_BRANCHES,
                "EXCEPTION_HANDLING" to ReadinessDimension.EXCEPTIONS_REWORK,
                "INPUT_ARTIFACT" to ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS,
                "OUTPUT_ARTIFACT" to ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS,
                "INPUTS_ARTIFACTS" to ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS,
                "OUTPUTS_ARTIFACTS" to ReadinessDimension.INPUTS_OUTPUTS_ARTIFACTS,
                "BPMN_PROCESS_SUITABILITY" to ReadinessDimension.BPMN_SUITABILITY,
                "SOURCE_TRACE" to ReadinessDimension.TRACEABILITY_TO_SOURCE,
            )
        cases.forEach { (alias, expected) ->
            val parsed: ReadinessDimension = objectMapper.readValue("\"$alias\"")
            assertEquals(expected, parsed, "alias '$alias' should resolve to $expected")
        }
    }

    @Test
    fun `aliases are read-only — canonical names still serialise out`() {
        val parsed: ReadinessDimension = objectMapper.readValue("\"START_STATES\"")
        assertEquals("\"START_TRIGGER\"", objectMapper.writeValueAsString(parsed))
    }

    @Test
    fun `canonical names still deserialise without going through alias path`() {
        ReadinessDimension.entries.forEach { dim ->
            val parsed: ReadinessDimension = objectMapper.readValue("\"${dim.name}\"")
            assertEquals(dim, parsed)
        }
    }

    @Test
    fun `aliases never collide within the enum`() {
        // Sanity: no alias accidentally maps to two values. If this ever failed Jackson would
        // have surfaced it at registration time — but this makes the invariant load-bearing in
        // case the alias table grows.
        assertEquals(ReadinessDimension.entries.size, ReadinessDimension.entries.toSet().size)
    }

    @Test
    fun `ClarificationQuestion deserialises the exact observed failure-signature synonyms`() {
        // Reproduces the failing payload (log line 996): relatedDimensions contained
        // "START_STATES" and "ACTORS_RESPONSIBILITY", both observed synonyms for canonical
        // ReadinessDimension names. Provider-agnostic: Jackson parses the raw JSON text
        // regardless of which provider/model produced it, so this covers the LLM-synonym
        // failure mode across providers without a live/mocked multi-provider integration test.
        val json =
            """
            {
              "id": "cq-1",
              "questionText": "What starts the process, and who is responsible?",
              "relatedDimensions": ["START_STATES", "ACTORS_RESPONSIBILITY"],
              "relatedMissingAreas": ["START_STATES", "ACTORS_RESPONSIBILITY"]
            }
            """.trimIndent()

        val question: ClarificationQuestion = objectMapper.readValue(json)

        assertEquals(
            listOf(ReadinessDimension.START_TRIGGER, ReadinessDimension.ACTORS_ROLES),
            question.relatedDimensions,
        )
        assertEquals(
            listOf(ReadinessDimension.START_TRIGGER, ReadinessDimension.ACTORS_ROLES),
            question.relatedMissingAreas,
        )
    }

    @Test
    fun `SourceEvidence deserialises cleanly when sourceType is omitted entirely`() {
        // The other half of the triggering failure signature: a missing required field
        // (sourceType had no default) rather than an unmapped synonym.
        val json =
            """
            {
              "text": "The customer submits an order."
            }
            """.trimIndent()

        val evidence: SourceEvidence = objectMapper.readValue(json)

        assertNull(evidence.sourceType)
        assertTrue(evidence.id.isEmpty())
    }

    @Test
    fun `SourceEvidence still deserialises a supplied sourceType`() {
        val json =
            """
            {
              "text": "The customer submits an order.",
              "sourceType": "ORIGINAL_INPUT"
            }
            """.trimIndent()

        val evidence: SourceEvidence = objectMapper.readValue(json)

        assertEquals(EvidenceSourceType.ORIGINAL_INPUT, evidence.sourceType)
    }
}
