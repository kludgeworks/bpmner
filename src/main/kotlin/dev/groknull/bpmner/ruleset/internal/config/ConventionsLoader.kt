/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.config

import dev.groknull.bpmner.ruleset.BpmnerLintConfig
import dev.groknull.bpmner.ruleset.internal.BpmnRulesUriConfig
import org.pkl.config.java.ConfigEvaluator
import org.pkl.config.kotlin.forKotlin
import org.pkl.config.kotlin.to
import org.pkl.core.ModuleSource
import org.pkl.core.PklException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

/**
 * Loads modeller-owned lint conventions from `bpmner.pkl` once at startup.
 *
 * Constructor-injects [BpmnRulesUriConfig] to create a `USES_COMPONENT` edge recognised by
 * Spring Modulith for the `ruleset` module's `DIRECT_DEPENDENCIES` bootstrap scan.
 * [BpmnRulesUriConfig] is registered via `@ConfigurationPropertiesScan` in [BpmnerApplication].
 * (ADR-007 Decision 1.1, updated for S4 dissolution of `config` module)
 */
@Configuration
internal class ConventionsLoader(private val config: BpmnRulesUriConfig) {
    private val logger = LoggerFactory.getLogger(ConventionsLoader::class.java)

    @Bean
    @ConditionalOnMissingBean
    fun bpmnerLintConfig(): BpmnerLintConfig {
        val uri = config.configUri?.trim()?.takeIf { it.isNotEmpty() }?.let(::fileOverrideUri)
            ?: URI.create(DEFAULT_CONFIG_URI)
        val pkl = try {
            ConfigEvaluator.preconfigured().forKotlin().use { evaluator ->
                evaluator.evaluate(ModuleSource.uri(uri))
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("Invalid BPMN lint config URI '$uri'.", e)
        } catch (e: PklException) {
            throw IllegalStateException(
                "Failed to evaluate BPMN lint config from $uri. Inspect bpmner.pkl for syntax errors, " +
                    "missing imports, or type-constraint violations.",
                e,
            )
        }

        val lintConfig = BpmnerLintConfig(
            profile = pkl.get("profile").to(),
            severityOverrides = pkl.get("severityOverrides").to<Map<String, String?>>(),
            discouragedLeadingVerbs = pkl.get("discouragedLeadingVerbs").to(),
            elementTypeWords = pkl.get("elementTypeWords").to(),
            allowedAcronyms = pkl.get("allowedAcronyms").to(),
            technicalTokens = pkl.get("technicalTokens").to(),
            discouragedBpmnTypes = pkl.get("discouragedBpmnTypes").to(),
        )
        logger.info(
            "BPMN lint conventions loaded from {} ({} element type word(s), {} allowed acronym(s))",
            uri.toString(),
            lintConfig.elementTypeWords.size,
            lintConfig.allowedAcronyms.size,
        )
        return lintConfig
    }

    companion object {
        const val DEFAULT_CONFIG_URI = "modulepath:/linter/pkl/bpmner.pkl"

        private fun fileOverrideUri(raw: String): URI {
            val uri = try {
                URI.create(raw)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Invalid BPMN lint config URI '$raw'.", e)
            }
            check(uri.scheme == "file") {
                "BPMN lint config override must be a file: URI, was '$raw'."
            }
            return uri
        }
    }
}
