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
@ActiveProfiles("githubmodels")
class GitHubModelsContextLoadTest {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Test
    fun `context loads and openai gpt-4o is registered without valid API key`() {
        // Assert that the application context loaded successfully
        assertThat(applicationContext).isNotNull()

        // Assert that the openai/gpt-4o model was dynamically registered
        val hasGpt4o = applicationContext.containsBean("openai/gpt-4o")
        assertThat(hasGpt4o).isTrue()

        // Assert that the openai/gpt-4o-mini model was dynamically registered
        val hasGpt4oMini = applicationContext.containsBean("openai/gpt-4o-mini")
        assertThat(hasGpt4oMini).isTrue()
    }
}
