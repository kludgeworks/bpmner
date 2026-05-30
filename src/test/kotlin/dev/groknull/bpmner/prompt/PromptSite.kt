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
 */
internal class PromptSite<T : Any>(
    val template: String,
    val outputType: Class<T>,
    private val renderer: JinjavaTemplateRenderer,
    private val objectMapper: ObjectMapper,
    private val model: () -> Map<String, Any>,
) {
    fun render(): String = renderer.renderLoadedTemplate(template, model())

    fun schemaFormat(): String = FilteringJacksonOutputConverter(outputType, objectMapper, Predicate { true }).format

    /** Full payload (prompt + schema-format) the LLM call ships, byte-aligned. */
    fun fullPayload(): String = render() + schemaFormat()
}
