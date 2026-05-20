/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.generation.OutlineMetrics
import org.springframework.stereotype.Component

@Component
internal class BpmnGeneratorMetrics {
    fun calculate(definition: BpmnDefinition): OutlineMetrics =
        OutlineMetrics(
            phaseCount = 1,
            branchCount = definition.nodes.count { it is BpmnExclusiveGateway },
            loopCount = definition.sequences.count { it.sourceRef == it.targetRef },
            subprocessCount = 0,
        )
}
