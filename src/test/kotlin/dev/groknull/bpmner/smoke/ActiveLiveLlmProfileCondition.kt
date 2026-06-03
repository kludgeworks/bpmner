/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.smoke

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext
import org.springframework.core.env.AbstractEnvironment
import org.springframework.core.env.StandardEnvironment

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(ActiveLiveLlmProfileCondition::class)
internal annotation class EnabledForLiveLlmProfile

internal class ActiveLiveLlmProfileCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        return evaluate(
            activeProfiles = StandardEnvironment().getProperty(AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME),
            env = System::getenv,
        ).toConditionEvaluationResult()
    }

    companion object {
        private const val ANTHROPIC_API_KEY = "ANTHROPIC_API_KEY"
        private const val GITHUB_TOKEN = "GITHUB_TOKEN"

        private val profileSplitter = Regex("[,\\s]+")
        private val providers = listOf(
            LiveLlmProvider(
                displayName = "Anthropic",
                profiles = setOf("anthropic"),
                tokenName = ANTHROPIC_API_KEY,
            ),
            LiveLlmProvider(
                displayName = "GitHub",
                profiles = setOf("github"),
                tokenName = GITHUB_TOKEN,
            ),
            LiveLlmProvider(
                displayName = "OpenAI",
                profiles = setOf("openai"),
                tokenName = "OPENAI_API_KEY",
            ),
            LiveLlmProvider(
                displayName = "Gemini",
                profiles = setOf("gemini"),
                tokenName = "GEMINI_API_KEY",
            ),
            LiveLlmProvider(
                displayName = "Mistral",
                profiles = setOf("mistral"),
                tokenName = "MISTRAL_API_KEY",
            ),
        )

        internal fun evaluate(
            activeProfiles: String?,
            env: (String) -> String?,
        ): Evaluation {
            val profiles = activeProfiles
                .orEmpty()
                .split(profileSplitter)
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
            val activeProviders = providers.filter { provider ->
                profiles.any { it in provider.profiles }
            }

            return when (activeProviders.size) {
                0 ->
                    Evaluation.disabled(
                        "no supported live LLM profile is active in ${AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME}; " +
                            "set one of anthropic, github, openai, gemini, or mistral",
                    )

                1 ->
                    activeProviders.single().requireToken(env)

                else ->
                    Evaluation.disabled(
                        "multiple live LLM profile families are active in " +
                            "${AbstractEnvironment.ACTIVE_PROFILES_PROPERTY_NAME}: " +
                            activeProviders.joinToString { it.displayName } +
                            "; select exactly one of anthropic, github, openai, gemini, or mistral",
                    )
            }
        }

        private data class LiveLlmProvider(
            val displayName: String,
            val profiles: Set<String>,
            val tokenName: String,
        ) {
            fun requireToken(env: (String) -> String?): Evaluation {
                val profileNames = profiles.sorted().joinToString("/")
                return if (env(tokenName).isNullOrBlank()) {
                    Evaluation.disabled("active live LLM profile $profileNames requires $tokenName")
                } else {
                    Evaluation.enabled("active live LLM profile $profileNames has $tokenName")
                }
            }
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
}
