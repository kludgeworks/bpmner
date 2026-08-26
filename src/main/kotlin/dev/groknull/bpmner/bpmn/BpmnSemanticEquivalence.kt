/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

/**
 * Compares two [BpmnDefinition]s for *semantic* equivalence — same process, ignoring presentation.
 *
 * Exists for the layout seam. Auto-layout replaces the XML wholesale while the `BpmnDefinition`
 * beside it is carried through untouched, so the two can silently disagree: alignment judges the
 * carried definition while the user receives the laid-out XML, and nothing reconciles them
 * (issue #746). Parsing the laid-out XML back and comparing closes that gap.
 *
 * The comparison is deliberately **structural, not exhaustive**. Layout legitimately adds diagram
 * interchange and may normalise attribute spelling, so comparing whole objects would fail on
 * cosmetic differences. What must not change is the process itself: which elements exist, how they
 * are wired, and the collaboration around them. Comparing identity and topology as *sets* also
 * keeps the check insensitive to document ordering, which the renderer and parser need not agree on.
 *
 * Returns a human-readable description per divergence; empty means equivalent.
 */
fun BpmnDefinition.semanticDivergenceFrom(other: BpmnDefinition): List<String> = buildList {
    compareScalar("processId", processId, other.processId)
    compareScalar("processName", processName, other.processName)

    compareIdSets("nodes", nodes.map { it.id }, other.nodes.map { it.id })
    // Edges carry their wiring, not just their identity: an edge that survives with a rewritten
    // target is a changed process, and comparing ids alone would miss it.
    compareIdSets(
        "sequence flows",
        sequences.map { "${it.id}(${it.sourceRef}->${it.targetRef})" },
        other.sequences.map { "${it.id}(${it.sourceRef}->${it.targetRef})" },
    )
    compareIdSets("participants", participants.map { it.id }, other.participants.map { it.id })
    compareIdSets("lanes", lanes.map { it.id }, other.lanes.map { it.id })
    compareIdSets(
        "message flows",
        messageFlows.map { "${it.id}(${it.sourceRef}->${it.targetRef})" },
        other.messageFlows.map { "${it.id}(${it.sourceRef}->${it.targetRef})" },
    )
}

private fun MutableList<String>.compareScalar(
    label: String,
    before: String,
    after: String,
) {
    if (before != after) add("$label changed: '$before' -> '$after'")
}

private fun MutableList<String>.compareIdSets(
    label: String,
    before: List<String>,
    after: List<String>,
) {
    val lost = before.toSet() - after.toSet()
    val gained = after.toSet() - before.toSet()
    if (lost.isNotEmpty()) add("$label lost: ${lost.sorted()}")
    if (gained.isNotEmpty()) add("$label gained: ${gained.sorted()}")
}
