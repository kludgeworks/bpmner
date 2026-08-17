/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

@SpringBootTest(properties = ["embabel.agent.platform.models.openrouter.api-key=test-key"])
@ActiveProfiles("llama")
class OpenRouterContextLoadTest {
    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `context loads and OpenRouter model is registered`() {
        assertThat(applicationContext.containsBean("meta-llama/llama-3.3-70b-instruct")).isTrue()
    }
}
