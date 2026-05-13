package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.core.OutlineMetrics
import org.springframework.stereotype.Component

@Component
internal class BpmnGeneratorMetrics {
    fun calculate(definition: BpmnDefinition): OutlineMetrics =
        OutlineMetrics(
            phaseCount = 1,
            branchCount = definition.nodes.count { it.type == NodeType.EXCLUSIVE_GATEWAY },
            loopCount = definition.sequences.count { it.sourceRef == it.targetRef },
            subprocessCount = 0,
        )
}
