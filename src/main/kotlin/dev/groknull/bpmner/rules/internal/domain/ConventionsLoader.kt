/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain

import dev.groknull.bpmner.config.BpmnConfig
import dev.groknull.bpmner.rules.BpmnerLintConfig
import org.pkl.config.java.ConfigEvaluator
import org.pkl.config.kotlin.forKotlin
import org.pkl.config.kotlin.to
import org.pkl.core.ModuleSource
import org.pkl.core.PklException
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI

/** Loads modeller-owned lint conventions from `bpmner.pkl` once at startup. */
@Configuration
internal class ConventionsLoader {
    private val logger = LoggerFactory.getLogger(ConventionsLoader::class.java)

    @Bean
    fun bpmnerLintConfig(config: BpmnConfig): BpmnerLintConfig {
        val uri = config.rules.configUri?.trim()?.takeIf { it.isNotEmpty() }?.let(::fileOverrideUri)
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
