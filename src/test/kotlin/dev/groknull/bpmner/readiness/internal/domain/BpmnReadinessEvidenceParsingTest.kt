/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness.internal.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the live shell blocker: the readiness LLM returns `evidence[]` items with only
 * `text` + `sourceType` (no `id`). When `SourceEvidence.id` was a non-null `@NotBlank` field, Jackson
 * threw `KotlinInvalidNullException`, Embabel retried 10×, and the whole process failed despite a
 * perfect READY verdict. `id` is now optional (defaulted), so the model output deserialises cleanly;
 * stable ids are assigned later by [BpmnReadinessPostChecker].
 */
class BpmnReadinessEvidenceParsingTest {
    private val mapper = jacksonObjectMapper()

    // Trimmed copy of a real Mistral readiness response: every evidence item omits `id`.
    private val modelJson =
        """
        {
          "verdict": "READY",
          "overallScore": 99,
          "dimensions": [
            { "dimension": "START_TRIGGER", "score": 90, "rationale": "Submission starts the process." }
          ],
          "missingAreas": [],
          "clarificationQuestions": [],
          "evidence": [
            { "text": "An employee submits a purchase request.", "sourceType": "ORIGINAL_INPUT" },
            { "text": "Finally the order is completed.", "sourceType": "ORIGINAL_INPUT" }
          ],
          "rationale": "The process is well-defined."
        }
        """.trimIndent()

    @Test
    fun `assessment with id-less evidence deserialises`() {
        val assessment = mapper.readValue<ProcessInputAssessment>(modelJson)

        assertEquals(2, assessment.evidence.size)
        assertTrue(assessment.evidence.all { it.id.isEmpty() }, "omitted ids default to empty, not a parse failure")
        assertEquals("An employee submits a purchase request.", assessment.evidence.first().text)
    }

    @Test
    fun `post-checker backfills the deserialised blank ids`() {
        val assessment = mapper.readValue<ProcessInputAssessment>(modelJson)

        val checked =
            BpmnReadinessPostChecker().apply(
                dev.groknull.bpmner.bpmn.internal.model.BpmnRequest(
                    "An employee submits a purchase request, then it is reviewed, and finally it is completed.",
                ),
                assessment,
            )

        assertTrue(checked.evidence.all { it.id.isNotBlank() })
        assertEquals(listOf("ev-1", "ev-2"), checked.evidence.map { it.id })
    }
}
