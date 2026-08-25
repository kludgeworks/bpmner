/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import dev.groknull.bpmner.bpmn.BpmnNode
import kotlin.reflect.full.isSubclassOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Expressiveness parity between `ProcessContract` and `BpmnDefinition`.
 *
 * The contract is an intermediate representation: the pipeline extracts it from prose and then
 * generates BPMN from it. Anything BPMN can express but the contract cannot is therefore
 * unreachable — not merely hard to produce, but *impossible*, no matter what the model does.
 *
 * That is not hypothetical. A subprocess containing a decision is ordinary BPMN — gateways carry
 * `parentRef` like any other node — but `ContractActivity.SubProcess.containedActivityIds` holds
 * activity ids only, and decisions live in a separate collection. So a contract that groups a
 * decision inside a subprocess has *no valid representation*: declaring the decision as a member
 * trips SUBPROCESS_MEMBER_NOT_FOUND, and leaving it out trips FLOW_CROSSES_SUBPROCESS_BOUNDARY on
 * every edge touching it. Extraction spent its entire corrective budget searching an empty solution
 * space, twice, deterministically, before anyone noticed (issue #742).
 *
 * Nothing compared the two models, so the gap survived from the feature's original commit — even
 * though the issue that specified embedded subprocesses (#191) explicitly described a subprocess
 * containing a gateway. This test is that comparison. Known gaps are declared, so a *new* one
 * fails the build rather than surfacing months later as an unfixable run.
 */
class ContractExpressivenessParityTest {
    private companion object {
        /** Well below the real count; only needs to be high enough to catch an empty walk. */
        const val EXPECTED_MINIMUM_NODE_TYPES = 15
    }

    /**
     * Which contract collection each BPMN node category is produced from. Enumerating the node
     * hierarchy is mechanical, so a newly added node type fails below with "no mapping declared"
     * rather than quietly inheriting someone's assumption.
     */
    private val nodeCategoryToContractSource: Map<String, ContractSource> = mapOf(
        "BpmnStartEvent" to ContractSource.START,
        "BpmnEndEvent" to ContractSource.END_STATE,
        "BpmnIntermediateThrowEvent" to ContractSource.INTERMEDIATE_THROW,
        "BpmnIntermediateCatchEvent" to ContractSource.INTERMEDIATE_THROW,
        "BpmnBoundaryEvent" to ContractSource.BOUNDARY_EVENT,
        "BpmnExclusiveGateway" to ContractSource.DECISION,
        "BpmnInclusiveGateway" to ContractSource.DECISION,
        "BpmnParallelGateway" to ContractSource.DECISION,
        "BpmnEventBasedGateway" to ContractSource.DECISION,
        "BpmnComplexGateway" to ContractSource.UNSUPPORTED_BY_PROFILE,
        "BpmnAdHocSubProcess" to ContractSource.UNSUPPORTED_BY_PROFILE,
        "BpmnUnrecognizedNode" to ContractSource.UNSUPPORTED_BY_PROFILE,
        "BpmnUserTask" to ContractSource.ACTIVITY,
        "BpmnServiceTask" to ContractSource.ACTIVITY,
        "BpmnScriptTask" to ContractSource.ACTIVITY,
        "BpmnBusinessRuleTask" to ContractSource.ACTIVITY,
        "BpmnSendTask" to ContractSource.ACTIVITY,
        "BpmnReceiveTask" to ContractSource.ACTIVITY,
        "BpmnManualTask" to ContractSource.ACTIVITY,
        "BpmnSubProcess" to ContractSource.ACTIVITY,
        "BpmnEventSubProcess" to ContractSource.ACTIVITY,
        "BpmnCallActivity" to ContractSource.ACTIVITY,
    )

    private enum class ContractSource(val nestableInSubprocess: Boolean) {
        /** `ContractActivity.SubProcess.containedActivityIds` names these, so they can be nested. */
        ACTIVITY(true),

        /** Contained implicitly: a boundary event is nested wherever its host activity is. */
        BOUNDARY_EVENT(true),

        DECISION(false),
        INTERMEDIATE_THROW(false),
        END_STATE(false),

        /** A process has exactly one start; nesting it is not a capability the contract needs. */
        START(false),

        /** Outside the supported BPMN profile, so parity does not apply. */
        UNSUPPORTED_BY_PROFILE(false),
    }

    /**
     * Categories BPMN can nest inside a subprocess but the contract cannot, each with the issue
     * tracking the gap. Delete an entry when its issue lands; the test then proves parity.
     */
    private val knownGaps: Map<ContractSource, String> = mapOf(
        ContractSource.DECISION to
            "issue #742 — SubProcess.containedActivityIds holds activity ids only, so a decision " +
            "inside a subprocess has no valid contract representation",
        ContractSource.INTERMEDIATE_THROW to
            "issue #742 — same containment limitation; intermediate throws cannot be nested either",
        ContractSource.END_STATE to
            "issue #742 — a subprocess with its own end state cannot be expressed",
    )

    @Test
    fun `every BPMN node type declares which contract element produces it`() {
        val nodeTypes = BpmnNode::class.sealedSubclasses
            .flatMap { if (it.isSealed) it.sealedSubclasses else listOf(it) }
            .filter { it.isSubclassOf(BpmnNode::class) }
            .mapNotNull { it.simpleName }
            .toSet()

        // Vacuity guard: if the sealed-hierarchy walk ever returns nothing, every assertion below
        // passes trivially and this test silently stops checking anything.
        assertTrue(
            nodeTypes.size >= EXPECTED_MINIMUM_NODE_TYPES,
            "expected the BpmnNode hierarchy to yield at least $EXPECTED_MINIMUM_NODE_TYPES types, " +
                "got ${nodeTypes.size} — the reflection walk is broken and this test proves nothing",
        )

        val undeclared = nodeTypes - nodeCategoryToContractSource.keys
        assertEquals(
            emptySet(),
            undeclared,
            "these BPMN node types have no declared contract source, so nobody has checked whether " +
                "the contract can express them: $undeclared",
        )
    }

    @Test
    fun `subprocess containment parity holds except for declared gaps`() {
        // Every BPMN node carries parentRef, so BPMN can nest any of them inside a subprocess.
        // The contract can only nest what its containment mechanism names.
        val categoriesInProfile = nodeCategoryToContractSource.values
            .filterNot { it == ContractSource.UNSUPPORTED_BY_PROFILE }
            .toSet()

        val cannotNest = categoriesInProfile.filterNot { it.nestableInSubprocess }.toSet()
        val undeclaredGaps = cannotNest - knownGaps.keys - setOf(ContractSource.START)

        assertTrue(
            undeclaredGaps.isEmpty(),
            "BPMN can nest these inside a subprocess but the contract cannot, and the gap is not " +
                "declared: $undeclaredGaps. Either extend the contract's containment, or declare " +
                "the gap in knownGaps with the issue tracking it.",
        )
    }

    @Test
    fun `declared gaps are still gaps`() {
        // If someone closes a gap without removing its entry, this registry becomes a lie and
        // stops proving anything.
        knownGaps.keys.forEach { source ->
            assertTrue(
                !source.nestableInSubprocess,
                "$source is declared as a known expressiveness gap but is now nestable — remove " +
                    "its entry from knownGaps",
            )
        }
    }
}
