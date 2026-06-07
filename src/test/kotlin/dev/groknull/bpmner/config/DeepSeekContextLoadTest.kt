/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("deepseek")
class DeepSeekContextLoadTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `context loads and deepseek-chat is registered without valid API key`() {
        // Assert that the application context loaded successfully
        assertThat(applicationContext).isNotNull()

        // Assert that the deepseek-chat model was dynamically registered
        val hasDeepSeekChat = applicationContext.containsBean("deepseek-chat")
        assertThat(hasDeepSeekChat).isTrue()
    }
}
