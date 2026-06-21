/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import dev.groknull.bpmner.authoring.OutlineMetrics
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnExclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnInclusiveGateway
import dev.groknull.bpmner.bpmn.BpmnParallelGateway
import org.springframework.stereotype.Component

@Component
internal class BpmnGeneratorMetrics {
    fun calculate(definition: BpmnDefinition): OutlineMetrics = OutlineMetrics(
        phaseCount = 1,
        exclusiveBranchCount = definition.nodes.count { it is BpmnExclusiveGateway },
        inclusiveBranchCount = definition.nodes.count { it is BpmnInclusiveGateway },
        parallelBranchCount = definition.nodes.count { it is BpmnParallelGateway },
        loopCount = definition.sequences.count { it.sourceRef == it.targetRef },
        subprocessCount = 0,
    )
}
