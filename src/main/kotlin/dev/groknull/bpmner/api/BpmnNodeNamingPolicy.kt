/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Decides which BPMN node kinds must carry a non-blank `name`. Pure logic over the api
 * hierarchy — no Jackson, no Jakarta. Compiled rules and the existing validator both
 * delegate here so the policy stays single-sourced.
 */
object BpmnNodeNamingPolicy {
    fun normalize(name: String?): String? = name?.takeIf { it.isNotBlank() }

    fun requiresName(
        node: BpmnNode,
        outgoingCount: Int,
    ): Boolean {
        // Every task subtype requires a name. Short-circuiting on the BpmnTask marker means
        // each new task kind added via the vocabulary epic (#196) automatically participates
        // without forcing this function to enumerate them individually — the rule is "all
        // tasks", not "this specific list of tasks".
        if (node.isTask()) return true
        return when (node) {
            is BpmnStartEvent,
            is BpmnIntermediateCatchEvent,
            is BpmnIntermediateThrowEvent,
            is BpmnBoundaryEvent,
            is BpmnEndEvent,
            -> true

            is BpmnExclusiveGateway -> outgoingCount > 1

            // Parallel gateways have no question to ask: fork is unconditional,
            // join is a barrier. Labels would be noise; keep them optional.
            is BpmnParallelGateway -> false

            // Tasks were already handled above via isTask(); these arms cover the marker
            // exhaustively for clarity even though they're unreachable.
            is BpmnUserTask,
            is BpmnServiceTask,
            is BpmnScriptTask,
            is BpmnBusinessRuleTask,
            is BpmnSendTask,
            is BpmnReceiveTask,
            is BpmnManualTask,
            -> true

            // Fallback for elements without a typed Kotlin class. No naming requirement —
            // the BpmnSubset rule flags the unrecognized element wholesale; adding a name
            // complaint on top would be noise.
            is BpmnUnrecognizedNode -> false

            // BpmnNode is non-sealed (see KDoc on BpmnNode) — the arms above cover every
            // canonical subtype. A hand-rolled BpmnNode impl outside the marker hierarchy
            // is treated as "requires a name" by default; safer than silently allowing blanks.
            else -> true
        }
    }

    fun missingNameMessage(node: BpmnNode): String {
        val nodeId = node.id.ifBlank { "<blank>" }
        return "node $nodeId name must not be blank for ${node.typeName}"
    }
}
