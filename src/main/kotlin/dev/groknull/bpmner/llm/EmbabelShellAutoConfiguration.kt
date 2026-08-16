/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.agent.spi.logging.ColorPalette
import com.embabel.agent.spi.logging.LoggingPersonality
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** Supplies the logging contract newly required by Embabel 1.5 shell commands. */
@AutoConfiguration
class EmbabelShellAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun loggingPersonality(palette: ColorPalette): LoggingPersonality = object : LoggingPersonality {
        override val colorPalette = palette
        override val logger = LoggerFactory.getLogger(EmbabelShellAutoConfiguration::class.java)
    }
}
