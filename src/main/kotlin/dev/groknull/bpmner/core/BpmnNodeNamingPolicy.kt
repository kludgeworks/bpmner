/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

object BpmnNodeNamingPolicy {
    fun normalize(name: String?): String? = name?.takeIf { it.isNotBlank() }

    fun requiresName(
        node: BpmnNode,
        outgoingCount: Int,
    ): Boolean =
        when (node) {
            is BpmnStartEvent,
            is BpmnUserTask,
            is BpmnServiceTask,
            is BpmnEndEvent,
            -> true

            is BpmnExclusiveGateway -> outgoingCount > 1

            // Parallel gateways have no question to ask: fork is unconditional,
            // join is a barrier. Labels would be noise; keep them optional.
            is BpmnParallelGateway -> false
        }

    fun missingNameMessage(node: BpmnNode): String {
        val nodeId = node.id.ifBlank { "<blank>" }
        return "node $nodeId name must not be blank for ${node.typeName}"
    }
}
