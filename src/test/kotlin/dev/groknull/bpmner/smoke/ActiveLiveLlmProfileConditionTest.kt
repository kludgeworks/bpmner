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
        val result = evaluate(profileProperty = "anth", env = mapOf("ANTHROPIC_API_KEY" to "sk-ant-test"))

        assertTrue(result.enabled, result.reason)
    }

    @Test
    fun `enables github profile when github token is present`() {
        val result = evaluate(profileEnvironment = "gh", env = mapOf("GITHUB_TOKEN" to "github_pat_test"))

        assertTrue(result.enabled, result.reason)
    }

    @Test
    fun `system property takes precedence over environment profile`() {
        val result = evaluate(
            profileProperty = "gh",
            profileEnvironment = "anth",
            env = mapOf("ANTHROPIC_API_KEY" to "sk-ant-test"),
        )

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("GITHUB_TOKEN"), result.reason)
    }

    @Test
    fun `disables when no supported profile is active`() {
        val result = evaluate(profileProperty = "test,verbose")

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("no supported live LLM profile"), result.reason)
    }

    @Test
    fun `disables when both provider families are active`() {
        val result = evaluate(
            profileProperty = "anth,github",
            env = mapOf("ANTHROPIC_API_KEY" to "sk-ant-test", "GITHUB_TOKEN" to "github_pat_test"),
        )

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("both Anthropic and GitHub"), result.reason)
    }

    @Test
    fun `disables when selected provider token is missing`() {
        val result = evaluate(profileProperty = "anthropic")

        assertFalse(result.enabled, result.reason)
        assertTrue(result.reason.contains("ANTHROPIC_API_KEY"), result.reason)
    }

    private fun evaluate(
        profileProperty: String? = null,
        profileEnvironment: String? = null,
        env: Map<String, String> = emptyMap(),
    ): ActiveLiveLlmProfileCondition.Evaluation = ActiveLiveLlmProfileCondition.evaluate(
        profileProperty = profileProperty,
        profileEnvironment = profileEnvironment,
        env = env::get,
    )
}
