/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import dev.groknull.bpmner.config.BpmnAlignmentConfig
import dev.groknull.bpmner.config.BpmnConfig
import dev.groknull.bpmner.config.BpmnContractConfig
import dev.groknull.bpmner.config.BpmnReadinessConfig
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class BpmnPipelineConfig {
    @Bean
    fun bpmnReadinessConfig(bpmnConfig: BpmnConfig): BpmnReadinessConfig = bpmnConfig.readiness

    @Bean
    fun bpmnContractConfig(bpmnConfig: BpmnConfig): BpmnContractConfig = bpmnConfig.contract

    @Bean
    fun bpmnAlignmentConfig(bpmnConfig: BpmnConfig): BpmnAlignmentConfig = bpmnConfig.alignment
}
