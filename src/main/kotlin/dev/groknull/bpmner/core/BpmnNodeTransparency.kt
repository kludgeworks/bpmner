/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.core

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
 * Future epic items (see [issue #196](https://github.com/kludgeworks/bpmner/issues/196)) each
 * contribute their own arm to the `when` below:
 *   - #182 INCLUSIVE_GATEWAY — unnamed single-outbound inclusive merges qualify as transparent.
 *   - #191 embedded subprocesses — the *implicit* start event inside a subprocess may be
 *     transparent; the host subprocess node itself is opaque.
 *   - #183 boundary events — the host's main outflow stays opaque; only the re-entry point of
 *     a recovery path may be transparent.
 *   - #185 typed start events — never transparent; their event-definition carries trigger
 *     semantics.
 *
 * The `when` is exhaustive over [BpmnNode], so any new sealed subtype forces a compile-time
 * decision about transparency. That's the scaling property: each vocabulary-extension PR
 * makes a deliberate decision here instead of silently inheriting "opaque" or "transparent".
 *
 * @param outgoingBySource sequence edges grouped by `sourceRef`, used to check the single-
 *   outbound criterion. Callers typically build this once and pass it through.
 */
internal fun BpmnNode.isSemanticallyTransparent(outgoingBySource: Map<String, List<BpmnEdge>>): Boolean = when (this) {
    is BpmnExclusiveGateway, is BpmnParallelGateway -> {
        name.isNullOrBlank() && outgoingBySource[id].orEmpty().size == 1
    }

    is BpmnStartEvent, is BpmnEndEvent, is BpmnUserTask, is BpmnServiceTask -> {
        false
    }

    // All task subtypes (#193) are opaque: each carries the business semantic of the work
    // being performed. Collapsing them through the fidelity walk would erase a contract-
    // realising step entirely.
    is BpmnScriptTask, is BpmnBusinessRuleTask, is BpmnSendTask,
    is BpmnReceiveTask, is BpmnManualTask,
    -> {
        false
    }

    // Typed event-position nodes (PR #199) all carry an event definition that gives them
    // independent semantic content (a timer trigger, an inbound message, a thrown signal,
    // a boundary recovery path). Treating any of them as transparent would let the fidelity
    // walk skip past a meaningful node — never correct.
    is BpmnIntermediateCatchEvent, is BpmnIntermediateThrowEvent, is BpmnBoundaryEvent -> {
        false
    }

    // Parser fallback for elements without a typed Kotlin class (#282). Never transparent —
    // we don't know enough about an unrecognized node to treat it as a routing-only pass-through.
    is BpmnUnrecognizedNode -> false
}
