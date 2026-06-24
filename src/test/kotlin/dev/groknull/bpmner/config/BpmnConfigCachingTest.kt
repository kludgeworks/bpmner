/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import com.embabel.agent.config.models.anthropic.getAnthropicCaching
import com.embabel.common.ai.model.LlmOptions
import dev.groknull.bpmner.alignment.BpmnAlignmentConfig
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringConfig
import dev.groknull.bpmner.contract.BpmnContractConfig
import dev.groknull.bpmner.readiness.BpmnReadinessConfig
import dev.groknull.bpmner.repair.BpmnRepairConfig
import dev.groknull.bpmner.ruleset.BpmnRulesConfig
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every pipeline role's LlmOptions carries the Anthropic prompt-caching extension
 * (systemPrompt + tools). Caching itself is a runtime concern only exercisable against a real
 * Anthropic endpoint, so this guards the one thing that IS unit-testable: that the config rides
 * on each role's LlmOptions. Under non-Anthropic profiles the extension is inert (read only by
 * Anthropic's options converter), so carrying it unconditionally is safe.
 *
 * After S4, each capability owns its own actor persona config. This test checks all of them.
 */
class BpmnConfigCachingTest {
    private val readinessConfig = BpmnReadinessConfig()
    private val contractConfig = BpmnContractConfig()
    private val alignmentConfig = BpmnAlignmentConfig()
    private val rulesConfig = BpmnRulesConfig()
    private val authoringConfig = BpmnAuthoringConfig()
    private val repairConfig = BpmnRepairConfig()

    @Test
    fun `every role enables anthropic system-prompt and tools caching`() {
        val roles: Map<String, LlmOptions> = mapOf(
            "generator" to authoringConfig.generator.llm,
            "repairer" to repairConfig.repairer.llm,
            "labelRepairer" to repairConfig.labelRepairer.llm,
            "patchRepairer" to repairConfig.patchRepairer.llm,
            "rewriteRepairer" to repairConfig.rewriteRepairer.llm,
            "readinessAssessor" to readinessConfig.readinessAssessor.llm,
            "contractExtractor" to contractConfig.contractExtractor.llm,
            "alignmentValidator" to alignmentConfig.alignmentValidator.llm,
            "linter" to rulesConfig.linter.llm,
        )

        roles.forEach { (role, llm) ->
            val caching = assertNotNull(llm.getAnthropicCaching(), "$role LlmOptions should carry the caching extension")
            assertTrue(caching.systemPrompt, "$role should cache the system prompt")
            assertTrue(caching.tools, "$role should cache the tool/schema definitions")
        }
    }
}
