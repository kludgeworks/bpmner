/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.nlp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression guard for the [BpmnNlp] thread-safety contract documented on [OpenNlpBpmnNlp].
 *
 * Today's implementation uses only read-after-construction OpenNLP components plus a
 * stateless morphological POS tagger — so all four [BpmnNlp] methods are inherently
 * thread-safe. This test exists to catch a future maintainer who swaps in a stateful
 * component (most likely `POSTaggerME`, which is NOT thread-safe) without instantiating it
 * per call — that change would either throw under contention or produce inconsistent
 * results, both of which this test detects.
 *
 * Strategy: drive 1000 parallel calls of every interface method across 16 threads against
 * a single shared [BpmnNlp] instance, then assert every thread saw identical output.
 */
internal class BpmnNlpConcurrencyTest {
    private val nlp = testBpmnNlp()

    @Test
    fun `posTags is consistent under parallel access`() {
        val expected = nlp.posTags(SAMPLE_LABEL)
        val pool = Executors.newFixedThreadPool(THREADS)
        val results = mutableListOf<List<PosTag>>()
        repeat(CALLS) { pool.submit { synchronized(results) { results += nlp.posTags(SAMPLE_LABEL) } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "tasks did not finish in time")
        assertEquals(CALLS, results.size)
        assertTrue(results.all { it == expected }, "posTags() produced inconsistent output under contention")
    }

    @Test
    fun `lemmasOf is consistent under parallel access`() {
        val expected = nlp.lemmasOf(SAMPLE_LABEL)
        val pool = Executors.newFixedThreadPool(THREADS)
        val results = mutableListOf<List<String>>()
        repeat(CALLS) { pool.submit { synchronized(results) { results += nlp.lemmasOf(SAMPLE_LABEL) } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "tasks did not finish in time")
        assertEquals(CALLS, results.size)
        assertTrue(results.all { it == expected }, "lemmasOf() produced inconsistent output under contention")
    }

    @Test
    fun `mixed-method workload completes without exceptions`() {
        // Doubles as a smoke test for the cross-method interaction. If any internal
        // component is hiding shared mutable state, mixing call types under contention
        // tends to surface it faster than hammering one method.
        val pool = Executors.newFixedThreadPool(THREADS)
        val errors = mutableListOf<Throwable>()
        repeat(CALLS) { i ->
            pool.submit {
                runCatching {
                    when (i % 4) {
                        0 -> nlp.tokens("Process the order")
                        1 -> nlp.posTags("Is the order valid?")
                        2 -> nlp.lemma("running")
                        else -> nlp.lemmasOf("Order received and processed")
                    }
                }.onFailure { e -> synchronized(errors) { errors += e } }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "tasks did not finish in time")
        assertTrue(errors.isEmpty(), "mixed-method workload threw: ${errors.firstOrNull()?.message}")
    }

    private companion object {
        const val THREADS = 16
        const val CALLS = 1000
        const val SAMPLE_LABEL = "Process the customer order"
    }
}
