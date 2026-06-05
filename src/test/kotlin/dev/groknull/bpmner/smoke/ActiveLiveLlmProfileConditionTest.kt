/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActiveLiveLlmProfileConditionTest {
    @Test
    fun `enables anthropic profile when anthropic token is present`() {
        val result = evaluate(activeProfiles = "anthropic", env = mapOf("ANTHROPIC_API_KEY" to "sk-ant-test"))

        assertTrue(result.enabled, result.reason)
    }

    @Test
    fun `enables deepseek profile when deepseek token is present`() {
        val result = evaluate(activeProfiles = "deepseek", env = mapOf("DEEPSEEK_API_KEY" to "sk-deepseek-test"))

        assertTrue(result.enabled, result.reason)
    }

    @Test
    fun `enables openrouter profile when openrouter token is present`() {
        val result = evaluate(activeProfiles = "openrouter", env = mapOf("OPENROUTER_API_KEY" to "sk-or-test"))

        assertTrue(result.enabled, result.reason)
    }

    @Test
    fun `system property takes precedence over environment profile`() {
        val result = evaluate(
            activeProfiles = "mistral",
            profileEnvironment = "anthropic",
            env = mapOf("ANTHROPIC_API_KEY" to "sk-ant-test"),
        )

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("MISTRAL_API_KEY"), result.reason)
    }

    @Test
    fun `disables when no supported profile is active`() {
        val result = evaluate(activeProfiles = "test,verbose")

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("no supported live LLM profile"), result.reason)
    }

    @Test
    fun `disables when both provider families are active`() {
        val result = evaluate(
            activeProfiles = "anthropic,mistral",
            env = mapOf("ANTHROPIC_API_KEY" to "sk-ant-test", "MISTRAL_API_KEY" to "mistral-test"),
        )

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("multiple live LLM profile families"), result.reason)
    }

    @Test
    fun `disables when selected provider token is missing`() {
        val result = evaluate(activeProfiles = "anthropic")

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("ANTHROPIC_API_KEY"), result.reason)
    }

    private fun evaluate(
        activeProfiles: String? = null,
        profileEnvironment: String? = null,
        env: Map<String, String> = emptyMap(),
    ): ActiveLiveLlmProfileCondition.Evaluation = ActiveLiveLlmProfileCondition.evaluate(
        activeProfiles = activeProfiles ?: profileEnvironment,
        env = env::get,
    )
}
