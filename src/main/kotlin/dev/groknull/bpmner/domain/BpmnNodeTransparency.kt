/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.domain

/**
 * Whether [this] node is "semantically transparent" — it carries no business meaning of its
 * own and exists only to route flow.
 *
 * Transparent nodes are *collapsed* by the fidelity checker when it tests whether a contract
 * branch's `nextRef` is reachable from the decision's gateway in the generated BPMN. This
 * accepts the legitimate generator pattern `gateway → unnamed-merge → target` (where the
 * generator inserts a synthesised converging join with no business question), without losing
 * the safety net for actually-missing branch flows.
 *
 * Today only one shape qualifies: an unnamed exclusive- or parallel-gateway node with exactly
 * one outbound edge — a pure converging join.
 *
 * The `when` covers every canonical [BpmnNode] subtype plus [BpmnUnrecognizedNode]; any other
 * implementation of the non-sealed [BpmnNode] interface falls through to `false` (opaque by
 * default — safer than silently treating an unknown node as routing-only). Add an explicit
 * `is` arm above the `else` when introducing a new canonical subtype with a different verdict.
 *
 * @param outgoingBySource sequence edges grouped by `sourceRef`, used to check the single-
 *   outbound criterion. Callers typically build this once and pass it through.
 */
internal fun BpmnNode.isSemanticallyTransparent(outgoingBySource: Map<String, List<BpmnEdge>>): Boolean = when (this) {
    is BpmnExclusiveGateway, is BpmnInclusiveGateway, is BpmnParallelGateway -> {
        name.isNullOrBlank() && outgoingBySource[id].orEmpty().size == 1
    }

    is BpmnStartEvent, is BpmnEndEvent, is BpmnUserTask, is BpmnServiceTask -> {
        false
    }

    // Tasks are opaque: each carries the business semantic of the work being performed.
    // Collapsing them through the fidelity walk would erase a contract-realising step.
    is BpmnScriptTask, is BpmnBusinessRuleTask, is BpmnSendTask,
    is BpmnReceiveTask, is BpmnManualTask,
    -> {
        false
    }

    // Typed event-position nodes all carry an event definition that gives them independent
    // semantic content (timer trigger, inbound message, thrown signal, boundary recovery).
    // Treating any of them as transparent would let the fidelity walk skip past a meaningful
    // node — never correct.
    is BpmnIntermediateCatchEvent, is BpmnIntermediateThrowEvent, is BpmnBoundaryEvent -> {
        false
    }

    // A subprocess is a composite activity wrapping its own flow — collapsing it would erase
    // a containment boundary the fidelity walk must preserve. Never transparent.
    is BpmnSubProcess -> false

    // A call activity is a real (composite) step that delegates to another process — it carries
    // semantic content, so it is never a routing-only pass-through.
    is BpmnCallActivity -> false

    // Fallback for elements without a typed Kotlin class. Never transparent — not enough
    // information to treat an unrecognized node as a routing-only pass-through.
    is BpmnUnrecognizedNode -> false

    // Safe default for any other (non-canonical) `BpmnNode` implementation. The interface
    // is non-sealed, so the compiler cannot enforce exhaustiveness here.
    else -> false
}
