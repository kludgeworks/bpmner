/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.ContractValidationCode
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch
import dev.groknull.bpmner.contract.withSourceIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class BpmnContractValidatorTest {
    private val validator = BpmnContractValidator()

    @Test
    fun `linear contract is valid`() {
        val contract = linearContract()
        val report = validator.validate(contract)
        assertTrue(report.isValid, "expected linear contract to be valid, got ${report.issues}")
    }

    @Test
    fun `branching contract is valid`() {
        val contract = branchingContract()
        val report = validator.validate(contract)
        assertTrue(report.isValid, "expected branching contract to be valid, got ${report.issues}")
    }

    @Test
    fun `exception path contract is valid`() {
        val contract = exceptionContract()
        val report = validator.validate(contract)
        assertTrue(report.isValid, "expected exception contract to be valid, got ${report.issues}")
    }

    @Test
    fun `weak contract surfaces missing trigger, end state, and insufficient activities`() {
        val contract =
            linearContract().copy(
                start = ContractStart(ContractTrigger.None(""), sources),
                activities = listOf(linearContract().activities.first()),
                endStates = emptyList(),
            )
        val codes =
            validator
                .validate(contract)
                .issues
                .map { it.code }
                .toSet()
        assertTrue(codes.contains(ContractValidationCode.MISSING_TRIGGER))
        assertTrue(codes.contains(ContractValidationCode.INSUFFICIENT_ACTIVITIES))
        assertTrue(codes.contains(ContractValidationCode.NO_END_STATE))
    }

    @Test
    fun `non-process contract surfaces multiple structural errors`() {
        val contract =
            ProcessContract(
                id = "contract-junk",
                processName = "",
                summary = "Random list of colors",
                trigger = "",
                activities = emptyList(),
                endStates = emptyList(),
            )
        val codes =
            validator
                .validate(contract)
                .issues
                .map { it.code }
                .toSet()
        assertTrue(codes.contains(ContractValidationCode.MISSING_PROCESS_NAME))
        assertTrue(codes.contains(ContractValidationCode.MISSING_TRIGGER))
        assertTrue(codes.contains(ContractValidationCode.NO_END_STATE))
        assertTrue(codes.contains(ContractValidationCode.INSUFFICIENT_ACTIVITIES))
    }

    @Test
    fun `assumption without trace produces an error`() {
        val contract =
            linearContract().copy(
                assumptions =
                listOf(
                    ContractAssumption(
                        id = "assumption-untracked",
                        text = "Customer is human",
                        sourceIds = emptyList(),
                    ),
                ),
            )
        val report = validator.validate(contract)
        assertFalse(report.isValid)
        assertEquals(
            ContractValidationCode.ASSUMPTION_WITHOUT_TRACE,
            report.issues.single().code,
        )
    }

    @Test
    fun `decision with a single branch produces an error`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val brokenDecision =
            originalDecision.copy(branches = listOf(originalDecision.branches.first()))
        val contract = branchingContract.copy(decisions = listOf(brokenDecision))
        val report = validator.validate(contract)
        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == ContractValidationCode.DECISION_BRANCH_TOO_FEW })
    }

    // The "blank label or condition" path is now enforced by Jakarta @NotBlank on the
    // sealed-subtype constructors, not by an explicit validator rule. A branch with a blank
    // label can no longer be constructed at all — the type system catches it earlier than the
    // old runtime check. This test therefore exercises the no-conditional-with-default rule
    // instead, which is the closest semantic successor.
    @Test
    fun `decision with only a default branch and no conditional alongside flags error`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val brokenDecision =
            originalDecision.copy(
                branches =
                listOf(
                    DefaultBranch(id = "branch-default-only-a", label = "A"),
                    DefaultBranch(id = "branch-default-only-b", label = "B"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(brokenDecision))
        val codes = validator.validate(contract).issues.map { it.code }
        // Two defaults trips both rules: multi-default AND no-conditional-alongside.
        assertTrue(codes.contains(ContractValidationCode.DECISION_MULTIPLE_DEFAULTS))
        assertTrue(codes.contains(ContractValidationCode.DECISION_DEFAULT_WITHOUT_CONDITIONAL))
    }

    @Test
    fun `decision with a single default branch alongside conditional is valid`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val decision =
            originalDecision.copy(
                branches =
                listOf(
                    ConditionalBranch(id = "br-yes", label = "Eligible", condition = "score >= 750"),
                    DefaultBranch(id = "br-fallback", label = "Manual review"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(decision))
        val report = validator.validate(contract)
        assertTrue(report.isValid, "expected single-default decision to be valid, got ${report.issues}")
    }

    @Test
    fun `default branch on parallel decision flags DEFAULT_BRANCH_ON_PARALLEL`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val parallelDecisionWithDefault =
            originalDecision.copy(
                kind = ContractGatewayKind.PARALLEL,
                branches =
                listOf(
                    UnconditionalBranch(id = "br-a", label = "Track A"),
                    DefaultBranch(id = "br-default", label = "Default"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(parallelDecisionWithDefault))
        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.DEFAULT_BRANCH_ON_PARALLEL))
    }

    @Test
    fun `conditional branch on parallel decision flags CONDITIONAL_BRANCH_ON_PARALLEL`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val parallelDecision =
            originalDecision.copy(
                kind = ContractGatewayKind.PARALLEL,
                branches =
                listOf(
                    UnconditionalBranch(id = "br-a", label = "Track A"),
                    ConditionalBranch(id = "br-misplaced", label = "B", condition = "x"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(parallelDecision))
        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.CONDITIONAL_BRANCH_ON_PARALLEL))
    }

    @Test
    fun `unconditional branch on exclusive decision flags UNCONDITIONAL_BRANCH_ON_EXCLUSIVE`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val brokenDecision =
            originalDecision.copy(
                branches =
                listOf(
                    ConditionalBranch(id = "br-yes", label = "Yes", condition = "x"),
                    UnconditionalBranch(id = "br-misplaced", label = "B"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(brokenDecision))
        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.UNCONDITIONAL_BRANCH_ON_EXCLUSIVE))
    }

    @Test
    fun `unconditional branch on inclusive decision flags UNCONDITIONAL_BRANCH_ON_INCLUSIVE`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val brokenDecision =
            originalDecision.copy(
                kind = ContractGatewayKind.INCLUSIVE,
                branches =
                listOf(
                    ConditionalBranch(id = "br-wrap", label = "Wrap", condition = "wrap?"),
                    UnconditionalBranch(id = "br-misplaced", label = "Always"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(brokenDecision))
        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.UNCONDITIONAL_BRANCH_ON_INCLUSIVE))
    }

    @Test
    fun `inclusive decision with conditional branches and default branch is valid`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val decision =
            originalDecision.copy(
                kind = ContractGatewayKind.INCLUSIVE,
                branches =
                listOf(
                    ConditionalBranch(id = "br-wrap", label = "Wrap", condition = "gift wrap requested"),
                    ConditionalBranch(id = "br-insert", label = "Insert", condition = "qualifies for insert"),
                    DefaultBranch(id = "br-none", label = "Skip add-ons"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(decision))
        val report = validator.validate(contract)
        // No INCLUSIVE-specific error codes should fire — default flows are valid on
        // inclusive decisions, and conditional branches are the expected branch shape.
        assertFalse(
            report.issues.any { it.code == ContractValidationCode.UNCONDITIONAL_BRANCH_ON_INCLUSIVE },
        )
        assertFalse(
            report.issues.any { it.code == ContractValidationCode.DEFAULT_BRANCH_ON_PARALLEL },
        )
    }

    @Test
    fun `trigger without trace links produces an error`() {
        val contract = linearContract().copy(start = ContractStart(ContractTrigger.None("Applicant submits an application")))
        val report = validator.validate(contract)
        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == ContractValidationCode.TRIGGER_WITHOUT_TRACE })
    }

    @Test
    fun `untraced activity produces an error`() {
        val original = linearContract()
        val activitiesWithoutTrace =
            original.activities.mapIndexed { index, activity ->
                if (index == 0) activity.withSourceIds(emptyList()) else activity
            }
        val contract = original.copy(activities = activitiesWithoutTrace)
        val report = validator.validate(contract)
        assertTrue(report.issues.any { it.code == ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE })
        assertEquals(
            ContractIssueSeverity.ERROR,
            report.issues.first { it.code == ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE }.severity,
        )
    }

    @Test
    fun `duplicate ids across contract elements produce an error`() {
        val contract =
            linearContract().copy(
                intermediateThrows =
                listOf(
                    ContractIntermediateThrow.Message(
                        id = "activity-review",
                        name = "Notify reviewer",
                        messageName = "review notification",
                        sourceIds = sources,
                    ),
                ),
            )

        val report = validator.validate(contract)

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == ContractValidationCode.DUPLICATE_CONTRACT_ELEMENT_ID })
    }

    @Test
    fun `blank intermediate throw payloads produce invalid contract item errors`() {
        val contract =
            linearContract().copy(
                intermediateThrows =
                listOf(
                    ContractIntermediateThrow.Message(
                        id = "throw-msg",
                        name = "Notify",
                        messageName = " ",
                        sourceIds = sources,
                    ),
                    ContractIntermediateThrow.Signal(
                        id = "throw-sig",
                        name = "Broadcast",
                        signalName = "",
                        sourceIds = sources,
                    ),
                    ContractIntermediateThrow.Escalation(
                        id = "throw-esc",
                        name = "Escalate",
                        escalationCode = " ",
                        sourceIds = sources,
                    ),
                ),
            )

        val issues = validator.validate(contract).issues

        assertEquals(3, issues.count { it.code == ContractValidationCode.INVALID_CONTRACT_ITEM })
    }

    private val sources = listOf("ev-source")

    private fun linearContract(): ProcessContract = ProcessContract(
        id = "contract-linear",
        processName = "Submit application",
        summary = "Application is submitted and reviewed.",
        trigger = "Applicant submits an application",
        triggerSourceIds = sources,
        activities =
        listOf(
            ContractActivity(
                id = "activity-receive",
                name = "Receive application",
                sourceIds = sources,
            ),
            ContractActivity(
                id = "activity-review",
                name = "Review application",
                sourceIds = sources,
            ),
        ),
        endStates =
        listOf(
            ContractEndState(
                id = "end-approved",
                name = "Application approved",
                sourceIds = sources,
            ),
        ),
    )

    private fun branchingContract(): ProcessContract {
        val linear = linearContract()
        return linear.copy(
            decisions =
            listOf(
                ContractDecision(
                    id = "decision-eligible",
                    question = "Is the applicant eligible?",
                    branches =
                    listOf(
                        ConditionalBranch(
                            id = "branch-yes",
                            label = "Eligible",
                            condition = "criteria met",
                        ),
                        ConditionalBranch(
                            id = "branch-no",
                            label = "Not eligible",
                            condition = "criteria not met",
                        ),
                    ),
                    sourceIds = sources,
                ),
            ),
        )
    }

    private fun exceptionContract(): ProcessContract {
        val branching = branchingContract()
        return branching.copy(
            endStates =
            branching.endStates +
                ContractEndState(
                    id = "end-rejected",
                    name = "Application rejected",
                    sourceIds = sources,
                ),
        )
    }
}
