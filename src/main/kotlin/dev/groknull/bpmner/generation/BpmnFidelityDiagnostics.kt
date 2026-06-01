/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import com.fasterxml.jackson.annotation.JsonClassDescription

/**
 * Categories of fidelity violations raised when a generated BpmnDefinition does not
 * faithfully encode the topology declared by its source ProcessContract.
 *
 * These complement [dev.groknull.bpmner.contract.ContractValidationCode] (which validates
 * the contract itself) and the bpmnlint rules (which validate the rendered XML). Fidelity
 * checks compare contract → BPMN topology.
 */
enum class BpmnFidelityCode {
    /**
     * A ContractDecision has no corresponding gateway node in the BPMN, or the matching
     * node is not a gateway type. Under the unified-id convention, the contract decision
     * id must equal the BPMN gateway node id.
     */
    DECISION_GATEWAY_MISSING,

    /**
     * The decision id resolves to a gateway node, but the gateway's kind does not match
     * the contract decision's `kind`. For example, a `ContractDecision(kind=PARALLEL)`
     * realised as `BpmnExclusiveGateway` — semantically wrong, because XOR means "pick
     * one branch" while parallel means "take all branches".
     */
    DECISION_GATEWAY_KIND_MISMATCH,

    /** A `ContractBranch.nextRef` points at an id that does not exist in the generated BPMN. */
    BRANCH_NEXT_REF_UNRESOLVED,

    /**
     * The decision's gateway exists but has fewer outbound sequence flows than the
     * decision has branches. The LLM has likely conflated branches into fewer outbound
     * flows.
     */
    GATEWAY_BRANCH_COUNT_INSUFFICIENT,

    /**
     * A branch's `nextRef` resolves to a real node, but no sequence flow connects the
     * decision's gateway to that target. Catches both missing loop back-edges and
     * missing forward-skip edges in a single check.
     */
    BRANCH_FLOW_MISSING,

    /**
     * A ContractActivity is realised by a BPMN task node whose kind doesn't match. For
     * example, `ContractActivity.Send` realised as `BpmnUserTask` — semantically wrong
     * because the LLM has flattened the kind discriminator away. Catches the failure mode
     * where the contract correctly identifies a fire-and-forget message (SEND) but the
     * BPMN generator emits a plain task that loses the messaging semantic.
     */
    ACTIVITY_TASK_KIND_MISMATCH,

    /**
     * A [dev.groknull.bpmner.contract.ContractDecision] has a
     * [dev.groknull.bpmner.contract.DefaultBranch] but no outbound edge from the corresponding
     * gateway has `isDefault = true`. The BPMN spec requires the gateway's `default` attribute
     * to point at the catch-all flow; without it process engines have no fallback when no
     * outbound condition matches.
     *
     * This code fires in the fidelity checker (generation-time via `validateOutline`) and from
     * the repair-loop evaluator (via `BpmnContractAwareValidator`).
     * [dev.groknull.bpmner.generation.DefaultFlowAssigner] runs
     * deterministically after every LLM call to set `isDefault`; this check is
     * the defence-in-depth that fires when the assigner could not resolve a match
     * (e.g. the gateway is missing entirely — caught first by [DECISION_GATEWAY_MISSING]).
     */
    DEFAULT_FLOW_MISSING,

    /**
     * A ContractEndState is realised by a BPMN end event whose `eventDefinition` shape
     * doesn't match the declared end-state kind. For example, `ContractEndState.Terminate`
     * realised as `BpmnEndEvent` with `BpmnNoneEventDefinition` — semantically wrong
     * because the terminate-scope behaviour is lost. Catches the failure mode where the
     * generator LLM flattens the end-state kind discriminator away in the BPMN pass.
     */
    END_EVENT_KIND_MISMATCH,

    /**
     * A ContractIntermediateThrow is missing, realised by a non-intermediate-throw node,
     * or realised by an intermediate throw whose `eventDefinition` shape doesn't match
     * the declared throw kind.
     */
    INTERMEDIATE_THROW_KIND_MISMATCH,
}

/**
 * Severity for fidelity issues. ERROR blocks the pipeline; WARNING is logged but
 * does not gate downstream stages.
 */
enum class BpmnFidelitySeverity {
    ERROR,
    WARNING,
}

@JsonClassDescription("Fidelity issue raised when generated BPMN topology diverges from the source contract")
data class BpmnFidelityIssue(
    val code: BpmnFidelityCode,
    val severity: BpmnFidelitySeverity,
    val message: String,
    val contractElementId: String? = null,
    val bpmnElementId: String? = null,
)

@JsonClassDescription("Structured report bundling fidelity issues found by BpmnContractFidelityChecker")
data class BpmnFidelityReport(
    val issues: List<BpmnFidelityIssue> = emptyList(),
) {
    val isValid: Boolean
        get() = issues.none { it.severity == BpmnFidelitySeverity.ERROR }

    companion object {
        val VALID = BpmnFidelityReport(emptyList())
    }
}
