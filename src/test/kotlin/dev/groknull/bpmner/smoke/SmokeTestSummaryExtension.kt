/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.LoggerFactory

/**
 * Prints a suite-wide cost/usage total after a live-LLM smoke suite, rendered via the framework's
 * [com.embabel.agent.core.LlmInvocationHistory.costInfoString] over every run's captured
 * `LlmInvocation`s (see [SuiteCostCapturer]). Resets the capturer before the suite so totals don't
 * leak across test classes sharing a JVM.
 */
class SmokeTestSummaryExtension :
    BeforeAllCallback,
    AfterAllCallback {
    private val logger = LoggerFactory.getLogger(SmokeTestSummaryExtension::class.java)

    override fun beforeAll(context: ExtensionContext) {
        SuiteCostCapturer.reset()
    }

    override fun afterAll(context: ExtensionContext) {
        val runs = SuiteCostCapturer.runCount()
        if (runs == 0) return

        val message = """

            ======================================================================
            TOTAL SUITE SUMMARY ($runs run(s) complete)
            ======================================================================
            ${SuiteCostCapturer.aggregateCostSummary()}
            ======================================================================

        """.trimIndent()
        logger.info(message)
        println(message)
    }
}
