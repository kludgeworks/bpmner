/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenerationExamplesTest {
    @Test
    fun `converging decisions example keeps both gateway paths to the same activity`() {
        val example = GenerationExamples.convergingDecisions
        val pathsToMarkPaid = example.sequences.filter { it.targetRef == "act-mark-order-paid" }

        assertEquals(setOf("dec-payment-received", "dec-payment-verified"), pathsToMarkPaid.map { it.sourceRef }.toSet())
        assertTrue(GenerationExamples.all.any { it.first == GenerationExamples.CONVERGING_DECISIONS_LABEL })
    }
}
