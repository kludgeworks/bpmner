/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal

import com.embabel.agent.observability.ObservabilityProperties
import com.embabel.agent.observability.tracing.ChatModelObservationFilter
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.observation.ChatModelObservationContext
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies #601's observability enablement: `application.yaml`'s
 * `embabel.agent.platform.observability` block binds to Embabel's real
 * [ObservabilityProperties] with the safe redaction default, and Embabel's own
 * [ChatModelObservationFilter] — the class `application.yaml`'s
 * `capture-message-content: false` actually governs — strips prompt/response content
 * from an LLM observation context when that default is in effect. This is the "smallest
 * thing that fails if the toggle regresses to `true` or stops applying."
 *
 * Live model/token/finish-reason attribute capture on `embabel.llm` spans is Spring AI's
 * and Embabel's own upstream-tested observation-convention machinery
 * (`EmbabelLlmObservationConvention`), wired automatically once observability is enabled
 * (asserted below via [ObservabilityProperties.isEnabled]); it is exercised end-to-end
 * against real providers by the existing CI `smoke-tests` matrix, not re-verified here
 * (mirrors ADR-588-06's precedent for OpenAI-path live verification, `PLAN-stage-3.md`).
 */
@SpringBootTest(classes = [ObservabilityRedactionTest.Config::class])
class ObservabilityRedactionTest {

    @EnableConfigurationProperties(ObservabilityProperties::class)
    class Config

    @Autowired
    private lateinit var observabilityProperties: ObservabilityProperties

    @Test
    fun `observability is enabled with content capture off by default`() {
        assertTrue(observabilityProperties.isEnabled, "embabel.agent.platform.observability.enabled must be true")
        assertFalse(
            observabilityProperties.isCaptureMessageContent,
            "embabel.agent.platform.observability.capture-message-content must default to false",
        )
    }

    @Test
    fun `the redaction default strips prompt and response content from LLM observation contexts`() {
        val filter = ChatModelObservationFilter(MAX_CONTENT_LENGTH, observabilityProperties.isCaptureMessageContent)

        val filtered = filter.map(observationContext())

        assertTrue(
            filtered.allKeyValues.none { it.key in CONTENT_KEYS },
            "redacted context must not carry prompt/response content keys; got: ${filtered.allKeyValues}",
        )
        assertTrue(
            filtered.allKeyValues.none { SECRET_PROMPT in it.value || SECRET_RESPONSE in it.value },
            "redacted context must not leak prompt/response text in any attribute; got: ${filtered.allKeyValues}",
        )
    }

    @Test
    fun `control - capturing content surfaces prompt and response text, proving the redaction toggle is real`() {
        val filter = ChatModelObservationFilter(MAX_CONTENT_LENGTH, true)

        val captured = filter.map(observationContext())

        assertTrue(
            captured.allKeyValues.any { it.key == "gen_ai.input.messages" && SECRET_PROMPT in it.value },
            "control: capturing content should surface the prompt text; got: ${captured.allKeyValues}",
        )
        assertTrue(
            captured.allKeyValues.any { it.key == "gen_ai.output.messages" && SECRET_RESPONSE in it.value },
            "control: capturing content should surface the response text; got: ${captured.allKeyValues}",
        )
    }

    private fun observationContext(): ChatModelObservationContext {
        val context = ChatModelObservationContext.builder()
            .prompt(Prompt(UserMessage(SECRET_PROMPT)))
            .provider("openai")
            .build()
        context.response = ChatResponse(listOf(Generation(AssistantMessage(SECRET_RESPONSE))))
        return context
    }

    private companion object {
        const val MAX_CONTENT_LENGTH = 10_000
        const val SECRET_PROMPT = "prompt-secret-marker-please-do-not-leak"
        const val SECRET_RESPONSE = "response-secret-marker-please-do-not-leak"
        val CONTENT_KEYS = setOf("gen_ai.input.messages", "gen_ai.output.messages")
    }
}
