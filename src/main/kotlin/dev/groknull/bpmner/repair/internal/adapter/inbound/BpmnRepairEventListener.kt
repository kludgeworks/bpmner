/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import dev.groknull.bpmner.authoring.BpmnGeneratedEvent
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@InfrastructureRing
@Component
internal class BpmnRepairEventListener {
    @EventListener
    fun onBpmnGenerated(_event: BpmnGeneratedEvent) {
        // This listener could trigger background refinement if needed,
        // but currently the orchestration is handled by Embabel agent chaining.
    }
}
