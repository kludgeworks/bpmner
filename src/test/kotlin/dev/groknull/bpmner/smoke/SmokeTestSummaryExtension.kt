/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.LoggerFactory
import org.springframework.test.context.junit.jupiter.SpringExtension

class SmokeTestSummaryExtension : AfterAllCallback {
    private val logger = LoggerFactory.getLogger(SmokeTestSummaryExtension::class.java)

    override fun afterAll(context: ExtensionContext) {
        val applicationContext = SpringExtension.getApplicationContext(context)
        val meterRegistry = applicationContext.getBeanProvider(MeterRegistry::class.java).getIfAvailable() ?: return

        // Query accumulated meters from registry
        val runs = meterRegistry.find("bpmner.llm.runs").counter()
            ?.count()?.toLong() ?: 0L
        if (runs == 0L) return

        val cost = meterRegistry.find("bpmner.llm.cost").counter()
            ?.count() ?: 0.0
        val promptTokens = meterRegistry.find("bpmner.llm.tokens")
            .tag("type", "prompt").counter()
            ?.count()?.toLong() ?: 0L
        val completionTokens = meterRegistry.find("bpmner.llm.tokens")
            .tag("type", "completion").counter()
            ?.count()?.toLong() ?: 0L
        val actions = meterRegistry.find("bpmner.llm.actions").counter()
            ?.count()?.toLong() ?: 0L

        val message = """

            ======================================================================
            TOTAL SUITE SUMMARY ($runs run(s) complete)
            ======================================================================
            Total LLM Cost:   $${"%.4f".format(cost)}
            Total Tokens:     $promptTokens prompt / $completionTokens completion
            Total Actions:    $actions
            ======================================================================

        """.trimIndent()
        logger.info(message)
        println(message)
    }
}
