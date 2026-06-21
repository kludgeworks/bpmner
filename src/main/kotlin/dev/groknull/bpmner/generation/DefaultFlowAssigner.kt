/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Deterministically propagates contract-side [DefaultBranch] semantics to the BPMN-side
 * [BpmnEdge.isDefault] flag. Runs after the generator LLM produces a [BpmnDefinition], before
 * any downstream validation.
 *
 * The motivating observation: the generator LLM is unreliable on this specific structural
 * mapping. The credit-tier run reproducibly produced exclusive-gateway outbound flows *without*
 * the `isDefault` discriminator and without conditions; the repair pipeline later compensated
 * by inventing placeholder conditions — semantically wrong and a wasted repair iteration. The
 * contract has the source of truth (a sealed [DefaultBranch] subtype is a type-system fact),
 * so we make the BPMN-side propagation deterministic and remove the LLM's chance to lose it.
 *
 * For each [contract.decisions] entry whose branches include exactly one [DefaultBranch], the
 * assigner finds the matching outbound edge from the gateway whose id equals the decision id
 * (the unified-id convention), and replaces it with `edge.copy(isDefault = true,
 * conditionExpression = null)`. Default flows MUST NOT carry a condition (BPMN spec).
 *
 * Matching strategy: a default branch with `nextRef = X` matches the edge with `targetRef = X`.
 * When `nextRef` is null, the match is ambiguous — the assigner logs a WARN and leaves the
 * definition unchanged, letting the validator catch genuine wiring failures.
 */
@Component
internal class DefaultFlowAssigner {
    private val logger = LoggerFactory.getLogger(DefaultFlowAssigner::class.java)

    fun assign(
        contract: ProcessContract,
        definition: BpmnDefinition,
    ): BpmnDefinition {
        val edgesBySource = definition.sequences.groupBy { it.sourceRef }
        val replacements =
            contract.decisions
                .mapNotNull { decision -> resolveReplacement(decision, edgesBySource) }
                .associateBy { it.id }
        if (replacements.isEmpty()) return definition
        val newSequences = definition.sequences.map { replacements[it.id] ?: it }
        return definition.copy(sequences = newSequences)
    }

    /**
     * Resolve the BpmnEdge that should be re-stamped with `isDefault = true` for [decision],
     * or null when no DefaultBranch exists / no unambiguous match is found. Logs WARNings for
     * the diagnostic paths and lets the validator catch genuine wiring failures.
     */
    private fun resolveReplacement(
        decision: dev.groknull.bpmner.contract.ContractDecision,
        edgesBySource: Map<String, List<BpmnEdge>>,
    ): BpmnEdge? {
        val default = decision.branches.filterIsInstance<DefaultBranch>().singleOrNull() ?: return null
        val outbound = edgesBySource[decision.id].orEmpty()
        if (outbound.isEmpty()) {
            logger.warn(
                "DefaultBranch '{}' on decision '{}' has no outbound edges from the gateway —" +
                    " skipping isDefault assignment; the validator will catch the wiring issue.",
                default.id,
                decision.id,
            )
            return null
        }
        val nextRef = default.nextRef
        val match =
            when {
                nextRef != null -> outbound.singleOrNull { it.targetRef == nextRef }
                outbound.size == 1 -> outbound.single()
                else -> null
            }
        if (match == null) {
            logger.warn(
                "DefaultBranch '{}' on decision '{}' (nextRef={}) could not be unambiguously" +
                    " matched to a single outbound edge from gateway '{}' (outbound targets: {})." +
                    " Skipping isDefault assignment.",
                default.id,
                decision.id,
                nextRef ?: "(none)",
                decision.id,
                outbound.map { it.targetRef },
            )
            return null
        }
        return match.copy(isDefault = true, conditionExpression = null)
    }
}
