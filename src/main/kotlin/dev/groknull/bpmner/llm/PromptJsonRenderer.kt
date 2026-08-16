/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectWriter
import tools.jackson.databind.json.JsonMapper

/**
 * Serialises a domain object for an LLM prompt: compact JSON, nulls and empty
 * collections omitted. Rebuilds its writer from the platform [ObjectMapper] rather than mutating
 * it, so the structured-output wire format used elsewhere is unaffected.
 */
@Component
class PromptJsonRenderer(objectMapper: JsonMapper) {
    private val writer: ObjectWriter = objectMapper.rebuild()
        .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_EMPTY) }
        .build()
        .writer()

    fun render(value: Any): String = writer.writeValueAsString(value)
}
