/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ProcessContract

/**
 * R5 (ADR-685-26): a corrective retry may not silently shrink the contract. Compares attempt
 * *N* against attempt *N+1* and reports any element or populated modifier field that attempt
 * *N* had but *N+1* lost, unless the diagnostic that drove the retry ([named]) pointed at it —
 * a targeted correction is allowed to change what it was asked to change.
 */
internal object ContractConservation {

    /** Returns human-readable drops; empty means [next] conserves everything [previous] had. */
    fun detectDrops(
        named: Set<String>,
        previous: ProcessContract,
        next: ProcessContract,
    ): List<String> {
        val drops = mutableListOf<String>()
        drops += removedIds(previous.activities.map { it.id }, next.activities.map { it.id }.toSet(), named, "activity")
        drops += removedIds(previous.decisions.map { it.id }, next.decisions.map { it.id }.toSet(), named, "decision")
        drops += removedIds(previous.endStates.map { it.id }, next.endStates.map { it.id }.toSet(), named, "end state")
        drops += removedIds(
            previous.intermediateThrows.map { it.id },
            next.intermediateThrows.map { it.id }.toSet(),
            named,
            "intermediate throw",
        )
        drops += removedIds(previous.actors.map { it.id }, next.actors.map { it.id }.toSet(), named, "actor")
        drops += removedBranches(previous, next, named)
        drops += droppedActivityModifiers(previous, next, named)
        return drops
    }

    private fun removedIds(
        previousIds: List<String>,
        nextIds: Set<String>,
        named: Set<String>,
        label: String,
    ): List<String> = previousIds
        .filter { it !in nextIds && it !in named }
        .map { "$label '$it' present in the previous attempt is missing" }

    // A branch is a child of its decision: a decision named by the driving diagnostic (e.g.
    // DECISION_BRANCH_TOO_FEW, which targets the decision id) may have its branches freely
    // restructured — that is the correction being asked for. A branch dropped under a decision
    // the diagnostic did *not* name is still a conservation violation.
    private fun removedBranches(
        previous: ProcessContract,
        next: ProcessContract,
        named: Set<String>,
    ): List<String> {
        val nextDecisionsById = next.decisions.associateBy { it.id }
        return previous.decisions.flatMap { previousDecision ->
            if (previousDecision.id in named) return@flatMap emptyList()
            val nextBranchIds = nextDecisionsById[previousDecision.id]?.branches?.map { it.id }?.toSet()
                ?: return@flatMap emptyList() // decision itself removed — reported by removedIds above
            previousDecision.branches
                .filter { it.id !in nextBranchIds && it.id !in named }
                .map { "branch '${it.id}' of decision '${previousDecision.id}' present in the previous attempt is missing" }
        }
    }

    private fun droppedActivityModifiers(
        previous: ProcessContract,
        next: ProcessContract,
        named: Set<String>,
    ): List<String> {
        val nextActivities = next.activities.associateBy { it.id }
        return previous.activities.flatMap { previousActivity ->
            val id = previousActivity.id
            if (id in named) return@flatMap emptyList()
            val nextActivity = nextActivities[id] ?: return@flatMap emptyList() // reported by removedIds above
            buildList {
                if (previousActivity.modifiers.iteration != null && nextActivity.modifiers.iteration == null) {
                    add("activity '$id' lost its iteration modifier")
                }
                if (previousActivity.modifiers.boundaryEvents.isNotEmpty() && nextActivity.modifiers.boundaryEvents.isEmpty()) {
                    add("activity '$id' lost its boundary events")
                }
                if (previousActivity.modifiers.loop != null && nextActivity.modifiers.loop == null) {
                    add("activity '$id' lost its loop modifier")
                }
            }
        }
    }
}
