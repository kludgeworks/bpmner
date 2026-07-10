/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain

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
import org.springframework.core.NativeDetector
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
        val rawConfigUri = config.configUri?.trim()?.takeIf { it.isNotEmpty() }
        if (rawConfigUri == null && NativeDetector.inNativeImage()) {
            return packagedNativeLintConfig().also {
                logger.info(
                    "BPMN lint conventions loaded from native packaged defaults ({} element type word(s), {} allowed acronym(s))",
                    it.elementTypeWords.size,
                    it.allowedAcronyms.size,
                )
            }
        }

        val uri = rawConfigUri?.let(::fileOverrideUri) ?: URI.create(DEFAULT_CONFIG_URI)
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

        internal fun packagedNativeLintConfig(): BpmnerLintConfig = BpmnerLintConfig(
            severityOverrides = mapOf(
                "act-verb-object-name" to "off",
                "act-activity-label-capitalization" to "off",
                "name-no-element-type-words" to "off",
                "name-uncommon-abbreviations" to "off",
            ),
        )

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
