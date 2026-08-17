/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.LoggingPersonality
import dev.groknull.bpmner.llm.EmbabelShellAutoConfiguration
import org.mockito.Mockito.mock
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@TestConfiguration(proxyBeanMethods = false)
@Import(EmbabelShellAutoConfiguration::class)
class EmbabelShellTestConfiguration {
    @Bean
    fun colorPalette(): ColorPalette = mock(ColorPalette::class.java)

    @Bean
    fun loggingPersonality(palette: ColorPalette): LoggingPersonality = object : LoggingPersonality {
        override val colorPalette = palette
        override val logger = LoggerFactory.getLogger(EmbabelShellTestConfiguration::class.java)
    }
}
