/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectWriter
import org.springframework.stereotype.Component

/**
 * Serialises a domain object for an LLM prompt: compact JSON, nulls and empty
 * collections omitted. Derives its writer from the platform [ObjectMapper] via
 * `copy()` rather than mutating it, so the structured-output wire format used
 * elsewhere is unaffected.
 */
@Component
class PromptJsonRenderer(objectMapper: ObjectMapper) {
    private val writer: ObjectWriter = objectMapper.copy()
        .setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY)
        .writer()

    fun render(value: Any): String = writer.writeValueAsString(value)
}
