package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.NodeType
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

@Service
@Component
internal class BpmnDefinitionValidator {
    @Suppress("CyclomaticComplexMethod") // six independent structural checks; splitting adds no clarity
    fun validate(definition: BpmnDefinition): List<String> {
        val errors = mutableListOf<String>()

        val nodeIds = definition.nodes.map { it.id.trim() }
        val edgeIds = definition.sequences.map { it.id.trim() }

        val duplicateNodeIds = nodeIds.groupBy { it }.filter { (id, all) -> id.isNotBlank() && all.size > 1 }.keys
        duplicateNodeIds.forEach { errors.add("duplicate node id: $it") }

        val duplicateEdgeIds = edgeIds.groupBy { it }.filter { (id, all) -> id.isNotBlank() && all.size > 1 }.keys
        duplicateEdgeIds.forEach { errors.add("duplicate edge id: $it") }

        val nodeIdSet = definition.nodes.map { it.id }.toSet()
        val incomingCounts = definition.sequences.groupingBy { it.targetRef }.eachCount()
        val outgoingCounts = definition.sequences.groupingBy { it.sourceRef }.eachCount()

        definition.nodes.forEach { node ->
            val requiresName =
                BpmnNodeNamingPolicy.requiresName(
                    node = node,
                    incomingCount = incomingCounts[node.id] ?: 0,
                    outgoingCount = outgoingCounts[node.id] ?: 0,
                )
            if (requiresName && node.name.isNullOrBlank()) {
                errors.add(BpmnNodeNamingPolicy.missingNameMessage(node))
            }
        }

        definition.sequences.forEach { edge ->
            if (edge.sourceRef !in nodeIdSet) {
                errors.add(
                    "edge ${edge.id.ifBlank { "<blank>" }} sourceRef '${edge.sourceRef}' does not match any node id",
                )
            }
            if (edge.targetRef !in nodeIdSet) {
                errors.add(
                    "edge ${edge.id.ifBlank { "<blank>" }} targetRef '${edge.targetRef}' does not match any node id",
                )
            }
            if (edge.sourceRef == edge.targetRef) {
                errors.add("edge ${edge.id.ifBlank { "<blank>" }} must not self-reference source and target")
            }
        }

        val startCount = definition.nodes.count { it.type == NodeType.START_EVENT }
        if (startCount == 0) errors.add("definition must contain at least one START_EVENT")

        val endCount = definition.nodes.count { it.type == NodeType.END_EVENT }
        if (endCount == 0) errors.add("definition must contain at least one END_EVENT")

        return errors
    }
}
