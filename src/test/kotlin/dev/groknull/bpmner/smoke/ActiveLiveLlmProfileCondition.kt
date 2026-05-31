/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtensionContext

internal class ActiveLiveLlmProfileCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        return evaluate(
            profileProperty = System.getProperty(SPRING_PROFILES_ACTIVE_PROPERTY),
            profileEnvironment = System.getenv(SPRING_PROFILES_ACTIVE_ENV),
            env = System::getenv,
        ).toConditionEvaluationResult()
    }

    companion object {
        private const val SPRING_PROFILES_ACTIVE_PROPERTY = "spring.profiles.active"
        private const val SPRING_PROFILES_ACTIVE_ENV = "SPRING_PROFILES_ACTIVE"
        private const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
        private const val GITHUB_TOKEN = "GITHUB_TOKEN"

        private val anthropicProfiles = setOf("anth", "anthropic")
        private val githubProfiles = setOf("gh", "github")
        private val profileSplitter = Regex("[,\\s]+")

        internal fun evaluate(
            profileProperty: String?,
            profileEnvironment: String?,
            env: (String) -> String?,
        ): Evaluation {
            val source = activeProfileSource(profileProperty, profileEnvironment)
            val profiles = source.value
                .orEmpty()
                .split(profileSplitter)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()

            val hasAnthropicProfile = profiles.any { it in anthropicProfiles }
            val hasGitHubProfile = profiles.any { it in githubProfiles }

            return when {
                hasAnthropicProfile && hasGitHubProfile ->
                    Evaluation.disabled(
                        "both Anthropic and GitHub live LLM profile families are active in ${source.name}; " +
                            "select exactly one of anth/anthropic or gh/github",
                    )

                hasAnthropicProfile ->
                    requireToken(
                        providerProfiles = "anth/anthropic",
                        tokenName = ANTHROPIC_API_KEY,
                        env = env,
                    )

                hasGitHubProfile ->
                    requireToken(
                        providerProfiles = "gh/github",
                        tokenName = GITHUB_TOKEN,
                        env = env,
                    )

                else ->
                    Evaluation.disabled(
                        "no supported live LLM profile is active in ${source.name}; " +
                            "set one of anth/anthropic or gh/github",
                    )
            }
        }

        private fun activeProfileSource(
            profileProperty: String?,
            profileEnvironment: String?,
        ): ProfileSource = if (!profileProperty.isNullOrBlank()) {
            ProfileSource(SPRING_PROFILES_ACTIVE_PROPERTY, profileProperty)
        } else {
            ProfileSource(SPRING_PROFILES_ACTIVE_ENV, profileEnvironment)
        }

        private fun requireToken(
            providerProfiles: String,
            tokenName: String,
            env: (String) -> String?,
        ): Evaluation = if (env(tokenName).isNullOrBlank()) {
            Evaluation.disabled("active live LLM profile $providerProfiles requires $tokenName")
        } else {
            Evaluation.enabled("active live LLM profile $providerProfiles has $tokenName")
        }
    }

    internal data class Evaluation(
        val enabled: Boolean,
        val reason: String,
    ) {
        fun toConditionEvaluationResult(): ConditionEvaluationResult = if (enabled) {
            ConditionEvaluationResult.enabled(reason)
        } else {
            ConditionEvaluationResult.disabled(reason)
        }

        companion object {
            fun enabled(reason: String): Evaluation = Evaluation(enabled = true, reason = reason)

            fun disabled(reason: String): Evaluation = Evaluation(enabled = false, reason = reason)
        }
    }

    private data class ProfileSource(
        val name: String,
        val value: String?,
    )
}
