/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import dev.groknull.bpmner.repair.internal.domain.BpmnLocalRepairCapabilityValidator
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
internal class BpmnRepairCapabilityStartupListener(
    private val validator: BpmnLocalRepairCapabilityValidator,
) {
    @EventListener(ContextRefreshedEvent::class)
    fun validateOnStartup() {
        validator.validateOnStartup()
    }
}
