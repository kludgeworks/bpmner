package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.ContractActivity
import dev.groknull.bpmner.core.ContractAssumption
import dev.groknull.bpmner.core.ContractBranch
import dev.groknull.bpmner.core.ContractDecision
import dev.groknull.bpmner.core.ContractEndState
import dev.groknull.bpmner.core.ContractIssueSeverity
import dev.groknull.bpmner.core.ContractValidationCode
import dev.groknull.bpmner.core.ProcessContract
import dev.groknull.bpmner.core.TraceLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                trigger = "",
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
                            traceLinks = emptyList(),
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

    @Test
    fun `branch missing both condition and label produces an error`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val blankBranch = ContractBranch(id = "branch-blank", label = " ", condition = null)
        val brokenDecision =
            originalDecision.copy(branches = originalDecision.branches + blankBranch)
        val contract = branchingContract.copy(decisions = listOf(brokenDecision))
        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.BRANCH_WITHOUT_CONDITION_OR_LABEL))
    }

    @Test
    fun `untraced activity produces an error`() {
        val original = linearContract()
        val activitiesWithoutTrace =
            original.activities.mapIndexed { index, activity ->
                if (index == 0) activity.copy(traceLinks = emptyList()) else activity
            }
        val contract = original.copy(activities = activitiesWithoutTrace)
        val report = validator.validate(contract)
        assertTrue(report.issues.any { it.code == ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE })
        assertEquals(
            ContractIssueSeverity.ERROR,
            report.issues.first { it.code == ContractValidationCode.CONTRACT_ITEM_WITHOUT_TRACE }.severity,
        )
    }

    private fun activityTrace(id: String) =
        TraceLink(
            id = "trace-$id",
            sourceId = "ev-source",
            targetId = id,
            classification = AlignmentClassification.SUPPORTED,
        )

    private fun linearContract(): ProcessContract =
        ProcessContract(
            id = "contract-linear",
            processName = "Submit application",
            summary = "Application is submitted and reviewed.",
            trigger = "Applicant submits an application",
            triggerTraceLinks = listOf(activityTrace("trigger")),
            activities =
                listOf(
                    ContractActivity(
                        id = "activity-receive",
                        name = "Receive application",
                        traceLinks = listOf(activityTrace("activity-receive")),
                    ),
                    ContractActivity(
                        id = "activity-review",
                        name = "Review application",
                        traceLinks = listOf(activityTrace("activity-review")),
                    ),
                ),
            endStates =
                listOf(
                    ContractEndState(
                        id = "end-approved",
                        name = "Application approved",
                        traceLinks = listOf(activityTrace("end-approved")),
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
                                ContractBranch(
                                    id = "branch-yes",
                                    label = "Eligible",
                                    condition = "criteria met",
                                ),
                                ContractBranch(
                                    id = "branch-no",
                                    label = "Not eligible",
                                    condition = "criteria not met",
                                ),
                            ),
                        traceLinks = listOf(activityTrace("decision-eligible")),
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
                        traceLinks = listOf(activityTrace("end-rejected")),
                    ),
        )
    }
}
