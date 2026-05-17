/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import dev.groknull.bpmner.core.BpmnAlignmentConfig
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnReadinessConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class BpmnPipelineConfig {
    @Bean
    fun bpmnReadinessConfig(bpmnConfig: BpmnConfig): BpmnReadinessConfig = bpmnConfig.readiness

    @Bean
    fun bpmnAlignmentConfig(bpmnConfig: BpmnConfig): BpmnAlignmentConfig = bpmnConfig.alignment
}
