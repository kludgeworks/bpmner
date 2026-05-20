/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.BpmnStartEvent
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

@Service
@Component
internal class BpmnDefinitionValidator {
    fun validate(definition: BpmnDefinition): List<String> {
        val errors = mutableListOf<String>()

        validateDuplicateIds(definition, errors)
        validateNames(definition, errors)
        validateEdges(definition, errors)
        validateRequiredEvents(definition, errors)

        return errors
    }

    private fun validateDuplicateIds(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodeIds = definition.nodes.map { it.id.trim() }
        val edgeIds = definition.sequences.map { it.id.trim() }

        nodeIds
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate node id: $it") }

        edgeIds
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate edge id: $it") }
    }

    private fun validateNames(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val outgoingCounts = definition.sequences.groupingBy { it.sourceRef }.eachCount()

        definition.nodes.forEach { node ->
            val requiresName =
                BpmnNodeNamingPolicy.requiresName(
                    node = node,
                    outgoingCount = outgoingCounts[node.id] ?: 0,
                )
            if (requiresName && node.name.isNullOrBlank()) {
                errors.add(BpmnNodeNamingPolicy.missingNameMessage(node))
            }
        }
    }

    private fun validateEdges(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodeIdSet = definition.nodes.map { it.id }.toSet()
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
    }

    private fun validateRequiredEvents(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        if (definition.nodes.none { it is BpmnStartEvent }) {
            errors.add("definition must contain at least one START_EVENT")
        }
        if (definition.nodes.none { it is BpmnEndEvent }) {
            errors.add("definition must contain at least one END_EVENT")
        }
    }
}
