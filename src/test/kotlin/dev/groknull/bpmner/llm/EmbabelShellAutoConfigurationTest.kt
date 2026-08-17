/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.LoggingPersonality
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class EmbabelShellAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(EmbabelShellAutoConfiguration::class.java))

    @Test
    fun `backs off when shell colors are unavailable`() {
        contextRunner.run { context ->
            assertThat(context).doesNotHaveBean(LoggingPersonality::class.java)
        }
    }

    @Test
    fun `supplies logging personality when shell colors are available`() {
        contextRunner.withBean(ColorPalette::class.java, Supplier { mock(ColorPalette::class.java) }).run { context ->
            assertThat(context).hasSingleBean(LoggingPersonality::class.java)
        }
    }

    @Test
    fun `respects an application supplied logging personality`() {
        contextRunner
            .withBean(ColorPalette::class.java, Supplier { mock(ColorPalette::class.java) })
            .withBean(LoggingPersonality::class.java, Supplier { mock(LoggingPersonality::class.java) })
            .run { context ->
                assertThat(context).hasSingleBean(LoggingPersonality::class.java)
            }
    }
}
