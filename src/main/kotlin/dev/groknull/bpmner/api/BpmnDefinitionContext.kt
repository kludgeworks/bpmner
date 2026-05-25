/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Pre-computed index over a [BpmnDefinition], wrapping the set / map structures that every
 * structural rule needs. Each compiled rule receives a single [BpmnDefinitionContext]
 * instance per evaluation rather than rebuilding its own lookup tables — a hand-rolled
 * rule that scans `definition.nodes` to find a node by id is doing the work the context
 * has already done.
 *
 * The field set mirrors the local variables that `validation.BpmnDefinitionValidator`
 * computes per call today. Future indexes (e.g. `edgesFrom`, `edgesTo`) can be added when
 * a compiled rule needs them — see #213 for the rule consolidation work.
 *
 * Indexes are evaluated eagerly when this object is constructed; they are immutable
 * snapshots of `definition` at that moment. Mutating `definition` after construction is
 * not supported.
 *
 * **Key normalisation**: every id index here uses **raw** ids exactly as they appear on
 * the definition. `nodeIds`, `sequenceIds`, `nodesById`, and the source-keyed maps below
 * are all consistent — `id in nodeIds` ↔ `id in nodesById` for the same id. The future
 * `DuplicateIdRule` (#213) is the one that needs trimmed comparison, and it'll do its own
 * `groupBy { it.id.trim() }` rather than asking this index to second-guess the raw input.
 */
class BpmnDefinitionContext(
    val definition: BpmnDefinition,
) {
    /** Raw BPMN node ids (membership-only — `Set` collapses any duplicates; the future
     *  `DuplicateIdRule` performs its own list-based detection). */
    val nodeIds: Set<String> = definition.nodes.map { it.id }.toSet()

    /** Raw sequence-flow (edge) ids. */
    val sequenceIds: Set<String> = definition.sequences.map { it.id }.toSet()

    /** Lookup from raw node id to the [BpmnNode] instance. Used by event-def, default-flow,
     *  and edge-resolution rules. */
    val nodesById: Map<String, BpmnNode> = definition.nodes.associateBy { it.id }

    /** Catalog ids referenced by message event definitions and send/receive tasks. */
    val messageIds: Set<String> = definition.messages.map { it.id }.toSet()

    /** Catalog ids referenced by signal event definitions. */
    val signalIds: Set<String> = definition.signals.map { it.id }.toSet()

    /** Catalog ids referenced by error event definitions. */
    val errorIds: Set<String> = definition.errors.map { it.id }.toSet()

    /** Catalog ids referenced by escalation event definitions. */
    val escalationIds: Set<String> = definition.escalations.map { it.id }.toSet()

    /** Outgoing-edge count per source node id — feeds the gateway / naming-policy rules. */
    val outgoingCounts: Map<String, Int> = definition.sequences.groupingBy { it.sourceRef }.eachCount()

    /** Default-flow edges grouped by source node id (each source should have at most one). */
    val defaultsBySource: Map<String, List<BpmnEdge>> =
        definition.sequences.filter { it.isDefault }.groupBy { it.sourceRef }
}
