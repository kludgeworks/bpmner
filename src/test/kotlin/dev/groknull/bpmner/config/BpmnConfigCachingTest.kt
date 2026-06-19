/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import com.embabel.agent.config.models.anthropic.getAnthropicCaching
import com.embabel.common.ai.model.LlmOptions
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every pipeline role's LlmOptions carries the Anthropic prompt-caching extension
 * (systemPrompt + tools). Caching itself is a runtime concern only exercisable against a real
 * Anthropic endpoint, so this guards the one thing that IS unit-testable: that the config rides
 * on each role's LlmOptions. Under non-Anthropic profiles the extension is inert (read only by
 * Anthropic's options converter), so carrying it unconditionally is safe.
 */
class BpmnConfigCachingTest {
    private val config = BpmnConfig()

    @Test
    fun `every role enables anthropic system-prompt and tools caching`() {
        val roles: Map<String, LlmOptions> = mapOf(
            "generator" to config.generator.llm,
            "repairer" to config.repairer.llm,
            "labelRepairer" to config.labelRepairer.llm,
            "patchRepairer" to config.patchRepairer.llm,
            "rewriteRepairer" to config.rewriteRepairer.llm,
            "readinessAssessor" to config.readinessAssessor.llm,
            "contractExtractor" to config.contractExtractor.llm,
            "alignmentValidator" to config.alignmentValidator.llm,
            "linter" to config.linter.llm,
        )

        roles.forEach { (role, llm) ->
            val caching = assertNotNull(llm.getAnthropicCaching(), "$role LlmOptions should carry the caching extension")
            assertTrue(caching.systemPrompt, "$role should cache the system prompt")
            assertTrue(caching.tools, "$role should cache the tool/schema definitions")
        }
    }
}
