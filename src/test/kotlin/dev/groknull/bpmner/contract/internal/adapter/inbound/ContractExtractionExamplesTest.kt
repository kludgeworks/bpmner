/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.adapter.inbound

import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.internal.domain.BpmnContractValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The few-shot examples are the model's only worked demonstration of contract topology, so an
 * example that would itself fail validation teaches the model to produce invalid contracts.
 *
 * `subProcessExample` shipped with no `flows` at all — it demonstrated none of the boundary rules
 * it exists to teach and would have been rejected by `@NotEmpty flows` plus the degree and
 * reachability rules. This suite exists so that cannot recur — see issue #743.
 */
class ContractExtractionExamplesTest {
    private val validator = BpmnContractValidator()

    private val allExamples: List<Pair<String, FlatProcessContract>> = listOf(
        ContractExtractionExamples.MESSAGE_END_LABEL to ContractExtractionExamples.messageEndExample,
        ContractExtractionExamples.SEND_TASK_LABEL to ContractExtractionExamples.sendTaskExample,
        ContractExtractionExamples.INTERMEDIATE_THROW_LABEL to ContractExtractionExamples.intermediateThrowExample,
        ContractExtractionExamples.SEND_THEN_NORMAL_LABEL to ContractExtractionExamples.sendThenNormalExample,
        ContractExtractionExamples.INCLUSIVE_GATEWAY_LABEL to ContractExtractionExamples.inclusiveGatewayExample,
        ContractExtractionExamples.BUSINESS_RULE_TASK_LABEL to ContractExtractionExamples.businessRuleTaskExample,
        ContractExtractionExamples.SUB_PROCESS_LABEL to ContractExtractionExamples.subProcessExample,
        ContractExtractionExamples.PARALLEL_GATEWAY_LABEL to ContractExtractionExamples.parallelGatewayExample,
    )

    @Test
    fun `every example that states a topology is a valid contract`() {
        allExamples
            // An example with no flows states no topology: it is a shape reference for one field
            // (e.g. "this is what a SEND activity looks like") and the degree/reachability rules
            // do not apply to it. Examples that DO state flows are making a topology claim and
            // must survive the same validator the model's output is held to.
            .filter { (_, example) -> example.flows.isNotEmpty() }
            .forEach { (label, example) ->
                val report = validator.validate(example.toSealed())
                assertTrue(
                    report.isValid,
                    "few-shot example '$label' is not a valid contract: ${report.issues.map { it.code }}",
                )
            }
    }

    @Test
    fun `the subprocess example demonstrates the boundary rule it exists to teach`() {
        val example = ContractExtractionExamples.subProcessExample
        val memberIds = example.subProcesses.flatMap { it.memberIds }.toSet()

        assertTrue(memberIds.isNotEmpty(), "subprocess example must declare members")
        assertTrue(example.flows.isNotEmpty(), "subprocess example must state its topology")

        // V11: no edge may have exactly one endpoint inside the group.
        val crossings = example.flows.filter { (it.from in memberIds) != (it.to in memberIds) }
        assertTrue(crossings.isEmpty(), "example crosses the subprocess boundary: $crossings")

        // The outer flow must enter and leave through the subprocess's own id.
        val subProcessId = example.subProcesses.single().id
        assertTrue(example.flows.any { it.to == subProcessId }, "nothing enters via the subprocess id")
        assertTrue(example.flows.any { it.from == subProcessId }, "nothing leaves via the subprocess id")
    }

    @Test
    fun `the parallel example keeps start single-outgoing and forks through one decision`() {
        val example = ContractExtractionExamples.parallelGatewayExample

        // The failure this example exists to prevent: wiring start straight to both strands.
        val startOutgoing = example.flows.filter { it.from == "start" }
        assertEquals(1, startOutgoing.size, "start must have exactly one outgoing edge, got $startOutgoing")

        val decision = example.decisions.single()
        assertEquals(ContractGatewayKind.PARALLEL, decision.kind)
        assertEquals(decision.id, startOutgoing.single().to, "start must flow into the parallel decision")
        assertTrue(
            decision.branches.all { it.kind == FlatBranchKind.UNCONDITIONAL },
            "PARALLEL branches must be UNCONDITIONAL",
        )

        // Both strands reconverge on a shared downstream activity; the join gateway is synthesised
        // downstream, never stated in the contract.
        val branchTargets = example.flows.filter { it.branchId != null }.map { it.to }
        val reconvergence = branchTargets.map { strand -> example.flows.filter { it.from == strand }.map { it.to } }
        assertEquals(1, reconvergence.flatten().distinct().size, "strands must reconverge on one activity")
    }
}
