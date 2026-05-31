/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompt

import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.function.Predicate

/**
 * One LLM call-site: a Jinja template, the model that drives it, and the output type whose
 * JSON schema is shipped to the LLM alongside the rendered prompt. Bundling these into one
 * value lets [PromptFixtures] expose four sites instead of twelve parallel render/schema/
 * model methods.
 *
 * Matches the production path: `FilteringJacksonOutputConverter.getFormat()` is what
 * `ChatClientLlmOperations.kt:199` calls when bpmner invokes `creating(T).fromTemplate(...)`,
 * and `Predicate { true }` is the default `LlmInteraction.fieldFilter` (`LlmInteraction.kt:128`)
 * that bpmner uses everywhere.
 *
 * [contribution] is the `PromptContributor` text the agent attaches via
 * `.withPromptContributor(request)` — it ships in the system message alongside the schema, so
 * [fullPayload] must include it to reflect what the LLM actually receives. Defaults to empty for
 * sites that carry no request contribution.
 */
internal class PromptSite<T : Any>(
    val template: String,
    val outputType: Class<T>,
    private val renderer: JinjavaTemplateRenderer,
    private val objectMapper: ObjectMapper,
    private val model: () -> Map<String, Any>,
    private val contribution: () -> String = { "" },
) {
    fun render(): String = renderer.renderLoadedTemplate(template, model())

    fun schemaFormat(): String = FilteringJacksonOutputConverter(outputType, objectMapper, Predicate { true }).format

    /**
     * Full payload the LLM call ships: request contribution (system message) + rendered template
     * (user message) + schema-format text (system message). Char-count is order-insensitive, so
     * the concatenation order here need not mirror Embabel's message assembly.
     */
    fun fullPayload(): String = contribution() + render() + schemaFormat()
}
