/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.domain

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEvent
import dev.groknull.bpmner.bpmn.BpmnNode
import dev.groknull.bpmner.bpmn.BpmnNoneEventDefinition
import dev.groknull.bpmner.bpmn.BpmnTask
import dev.groknull.bpmner.contract.ProcessContract

/**
 * Outline side of retry conservation: a corrective outline retry may not silently shrink the BPMN it
 * already realised. The comparison is scoped to *contract-realised* node ids (the unified-id
 * convention) — synthesised routing nodes (`StartEvent_1`, `Gateway_join_1`) and all edges are the
 * model's own topology call and are deliberately excluded, or every legitimate retry would be
 * rejected.
 */
internal object OutlineConservation {

    /** Returns human-readable drops; empty means [next] conserves everything [previous] had. */
    fun detectDrops(
        named: Set<String>,
        contract: ProcessContract,
        previous: BpmnDefinition,
        next: BpmnDefinition,
    ): List<String> {
        val contractIds = contractElementIds(contract)
        val previousNodes = previous.nodes.associateBy { it.id }.filterKeys { it in contractIds }
        val nextNodes = next.nodes.associateBy { it.id }.filterKeys { it in contractIds }

        val removed = (previousNodes.keys - nextNodes.keys)
            .filter { it !in named }
            .map { "node '$it' present in the previous attempt is missing" }
        val fieldsLost = previousNodes.mapNotNull { (id, previousNode) ->
            if (id in named) return@mapNotNull null
            val nextNode = nextNodes[id] ?: return@mapNotNull null // reported as a dropped node above
            lostField(id, previousNode, nextNode)
        }
        return removed + fieldsLost.flatten()
    }

    private fun lostField(
        id: String,
        previousNode: BpmnNode,
        nextNode: BpmnNode,
    ): List<String> = buildList {
        if (previousNode is BpmnTask && nextNode is BpmnTask) {
            if (previousNode.multiInstance != null && nextNode.multiInstance == null) {
                add("task '$id' lost its multi-instance marker")
            }
            if (previousNode.standardLoop != null && nextNode.standardLoop == null) {
                add("task '$id' lost its standard-loop marker")
            }
        }
        if (previousNode is BpmnEvent && nextNode is BpmnEvent) {
            val previousPopulated = previousNode.eventDefinition !is BpmnNoneEventDefinition
            val nextPopulated = nextNode.eventDefinition !is BpmnNoneEventDefinition
            if (previousPopulated && !nextPopulated) add("event '$id' lost its event definition")
        }
    }

    private fun contractElementIds(contract: ProcessContract): Set<String> =
        contract.activities.map { it.id }.toSet() +
            contract.decisions.map { it.id } +
            contract.decisions.flatMap { decision -> decision.branches.map { it.id } } +
            contract.endStates.map { it.id } +
            contract.intermediateThrows.map { it.id } +
            contract.actors.map { it.id }
}
