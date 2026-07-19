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

@SpringBootTest
@ActiveProfiles("gemini")
class GeminiContextLoadTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `context loads under the gemini profile`() {
        assertThat(applicationContext).isNotNull()
    }
}
