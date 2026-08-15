/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.bpmn.BoundaryEventKind
import dev.groknull.bpmner.contract.ActivityModifiers
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractBoundaryEvent
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractFlow
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.ContractIntermediateThrow
import dev.groknull.bpmner.contract.ContractIssueSeverity
import dev.groknull.bpmner.contract.ContractStart
import dev.groknull.bpmner.contract.ContractTrigger
import dev.groknull.bpmner.contract.ContractValidationCode
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.EventGatewayBranch
import dev.groknull.bpmner.contract.EventTriggerKind
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch
import dev.groknull.bpmner.contract.withSourceIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("TooManyFunctions", "LargeClass")
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
    fun `call activity with a calledElement is valid`() {
        val contract =
            linearContract().copy(
                activities =
                listOf(
                    ContractActivity(id = "activity-receive", name = "Receive order", sourceIds = sources),
                    ContractActivity.CallActivity(
                        id = "activity-fulfil",
                        name = "Fulfil order",
                        calledElement = "fulfil-order",
                        sourceIds = sources,
                    ),
                ),
                flows = listOf(
                    ContractFlow.Sequence(from = "start", to = "activity-receive"),
                    ContractFlow.Sequence(from = "activity-receive", to = "activity-fulfil"),
                    ContractFlow.Sequence(from = "activity-fulfil", to = "end-approved"),
                ),
            )
        assertTrue(validator.validate(contract).isValid, "got: ${validator.validate(contract).issues}")
    }

    @Test
    fun `call activity without a calledElement surfaces CALL_ACTIVITY_MISSING_TARGET`() {
        val contract =
            linearContract().copy(
                activities =
                listOf(
                    ContractActivity(id = "activity-receive", name = "Receive order", sourceIds = sources),
                    ContractActivity.CallActivity(
                        id = "activity-fulfil",
                        name = "Fulfil order",
                        calledElement = " ",
                        sourceIds = sources,
                    ),
                ),
            )
        val codes = validator.validate(contract).issues.map { it.code }.toSet()
        assertTrue(codes.contains(ContractValidationCode.CALL_ACTIVITY_MISSING_TARGET), "got: $codes")
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
                start = ContractStart(ContractTrigger.None("")),
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
        val contract = branchingContract.copy(
            decisions = listOf(decision),
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "activity-receive"),
                ContractFlow.Sequence(from = "activity-receive", to = "decision-eligible"),
                ContractFlow.Branch(from = "decision-eligible", to = "activity-review", branchId = "br-yes"),
                ContractFlow.Branch(from = "decision-eligible", to = "end-approved", branchId = "br-fallback"),
                ContractFlow.Sequence(from = "activity-review", to = "end-approved"),
            ),
        )
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

    // An INCLUSIVE decision may carry a DEFAULT branch for the path taken when none of its
    // conditions hold; it routes through the same checks as EXCLUSIVE. Asserted as fully valid
    // rather than as the absence of two codes, so an unrelated rule firing on this shape fails.
    @Test
    fun `inclusive decision with conditional branches and a default branch is valid`() {
        val branching = branchingContract()
        val decision =
            branching.decisions.first().copy(
                kind = ContractGatewayKind.INCLUSIVE,
                branches =
                listOf(
                    ConditionalBranch(id = "br-wrap", label = "Wrap", condition = "gift wrap requested"),
                    ConditionalBranch(id = "br-insert", label = "Insert", condition = "qualifies for insert"),
                    DefaultBranch(id = "br-none", label = "Skip add-ons"),
                ),
            )
        val contract = branching.copy(
            decisions = listOf(decision),
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "activity-receive"),
                ContractFlow.Sequence(from = "activity-receive", to = "decision-eligible"),
                ContractFlow.Branch(from = "decision-eligible", to = "activity-review", branchId = "br-wrap"),
                ContractFlow.Branch(from = "decision-eligible", to = "activity-review", branchId = "br-insert"),
                // The bypass: taken when neither add-on applies, routed by its own DEFAULT branch
                // rather than as an unlabelled edge.
                ContractFlow.Branch(from = "decision-eligible", to = "end-approved", branchId = "br-none"),
                ContractFlow.Sequence(from = "activity-review", to = "end-approved"),
            ),
        )

        val report = validator.validate(contract)

        assertTrue(
            report.isValid,
            "an INCLUSIVE decision with a DEFAULT bypass branch must be valid; got ${report.issues}",
        )
    }

    @Test
    fun `conditional branch on event-based decision flags NON_EVENT_BRANCH_ON_EVENT_BASED`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val brokenDecision =
            originalDecision.copy(
                kind = ContractGatewayKind.EVENT_BASED,
                branches =
                listOf(
                    EventGatewayBranch(
                        id = "br-pay",
                        label = "Payment confirmed",
                        triggerKind = EventTriggerKind.MESSAGE,
                        triggerDetail = "payment confirmation",
                    ),
                    ConditionalBranch(id = "br-misplaced", label = "Score check", condition = "score >= 750"),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(brokenDecision))
        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.NON_EVENT_BRANCH_ON_EVENT_BASED))
    }

    @Test
    fun `event-gateway branch on exclusive decision flags EVENT_BRANCH_ON_NON_EVENT_BASED`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val brokenDecision =
            originalDecision.copy(
                branches =
                listOf(
                    ConditionalBranch(id = "br-yes", label = "Yes", condition = "x"),
                    EventGatewayBranch(
                        id = "br-misplaced",
                        label = "Timeout",
                        triggerKind = EventTriggerKind.TIMER,
                        triggerDetail = "PT24H",
                    ),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(brokenDecision))
        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.EVENT_BRANCH_ON_NON_EVENT_BASED))
    }

    @Test
    fun `event-based decision with only event-gateway branches is valid`() {
        val branchingContract = branchingContract()
        val originalDecision = branchingContract.decisions.first()
        val decision =
            originalDecision.copy(
                kind = ContractGatewayKind.EVENT_BASED,
                branches =
                listOf(
                    EventGatewayBranch(
                        id = "br-pay",
                        label = "Payment confirmed",
                        triggerKind = EventTriggerKind.MESSAGE,
                        triggerDetail = "payment confirmation",
                    ),
                    EventGatewayBranch(
                        id = "br-timeout",
                        label = "Timed out",
                        triggerKind = EventTriggerKind.TIMER,
                        triggerDetail = "PT24H",
                    ),
                ),
            )
        val contract = branchingContract.copy(decisions = listOf(decision))
        val report = validator.validate(contract)
        assertFalse(
            report.issues.any { it.code == ContractValidationCode.NON_EVENT_BRANCH_ON_EVENT_BASED },
        )
        assertFalse(
            report.issues.any { it.code == ContractValidationCode.EVENT_BRANCH_ON_NON_EVENT_BASED },
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
                ),
            )

        val issues = validator.validate(contract).issues

        assertEquals(1, issues.count { it.code == ContractValidationCode.INVALID_CONTRACT_ITEM })
    }

    private val sources = listOf("ev-source")

    @Test
    fun `valid embedded subprocess passes`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + ContractActivity.SubProcess(
                id = "sub-assess",
                name = "Assess",
                containedActivityIds = base.activities.map { it.id },
                sourceIds = sources,
            ),
            // The outer flow crosses through the subprocess's own id (V11), never through a
            // member id directly. Interior connectivity (V12) is the member-to-member edge.
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "sub-assess"),
                ContractFlow.Sequence(from = "activity-receive", to = "activity-review"),
                ContractFlow.Sequence(from = "sub-assess", to = "end-approved"),
            ),
        )

        val report = validator.validate(contract)
        assertTrue(report.isValid, "expected valid subprocess, got ${report.issues}")
    }

    @Test
    fun `subprocess with a dangling member id flags SUBPROCESS_MEMBER_NOT_FOUND`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + ContractActivity.SubProcess(
                id = "sub-assess",
                name = "Assess",
                containedActivityIds = listOf("activity-receive", "act-ghost"),
                sourceIds = sources,
            ),
        )

        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.SUBPROCESS_MEMBER_NOT_FOUND))
    }

    @Test
    fun `activity claimed by two subprocesses flags SUBPROCESS_MEMBER_SHARED`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + listOf(
                ContractActivity.SubProcess(
                    id = "sub-a",
                    name = "A",
                    containedActivityIds = listOf("activity-receive"),
                    sourceIds = sources,
                ),
                ContractActivity.SubProcess(
                    id = "sub-b",
                    name = "B",
                    containedActivityIds = listOf("activity-receive"),
                    sourceIds = sources,
                ),
            ),
        )

        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.SUBPROCESS_MEMBER_SHARED))
    }

    @Test
    fun `subprocess naming another subprocess as a member flags SUBPROCESS_NESTED_MEMBER`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + listOf(
                ContractActivity.SubProcess(
                    id = "sub-inner",
                    name = "Inner",
                    containedActivityIds = listOf("activity-receive"),
                    sourceIds = sources,
                ),
                ContractActivity.SubProcess(
                    id = "sub-outer",
                    name = "Outer",
                    containedActivityIds = listOf("activity-review", "sub-inner"),
                    sourceIds = sources,
                ),
            ),
        )

        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.SUBPROCESS_NESTED_MEMBER))
    }

    @Test
    fun `empty subprocess flags SUBPROCESS_EMPTY`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + ContractActivity.SubProcess(
                id = "sub-empty",
                name = "Empty",
                containedActivityIds = emptyList(),
                sourceIds = sources,
            ),
        )

        val codes = validator.validate(contract).issues.map { it.code }
        assertTrue(codes.contains(ContractValidationCode.SUBPROCESS_EMPTY))
    }

    @Test
    fun `duplicate member within one embedded subprocess is not misreported as shared`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + ContractActivity.SubProcess(
                id = "sub-dup",
                name = "Dup",
                containedActivityIds = listOf("activity-receive", "activity-receive"),
                sourceIds = sources,
            ),
        )

        val codes = validator.validate(contract).issues.map { it.code }
        assertFalse(codes.contains(ContractValidationCode.SUBPROCESS_MEMBER_SHARED))
    }

    @Test
    fun `V1 - a flow endpoint that resolves to no declared element fails FLOW_ENDPOINT_NOT_FOUND`() {
        val contract = linearContract().copy(
            flows = linearContract().flows + ContractFlow.Sequence(from = "activity-review", to = "act-hallucinated"),
        )
        val issue = validator.validate(contract).issues.single { it.code == ContractValidationCode.FLOW_ENDPOINT_NOT_FOUND }
        assertEquals("act-hallucinated", issue.targetId)
    }

    @Test
    fun `V2 - a flow from an element to itself fails FLOW_SELF_LOOP`() {
        val contract = linearContract().copy(
            flows = linearContract().flows + ContractFlow.Sequence(from = "activity-review", to = "activity-review"),
        )
        assertTrue(validator.validate(contract).issues.any { it.code == ContractValidationCode.FLOW_SELF_LOOP })
    }

    @Test
    fun `V7 - a declared but unreachable activity fails ELEMENT_UNREACHABLE_FROM_START, naming it`() {
        val linear = linearContract()
        val contract = linear.copy(
            activities = linear.activities + ContractActivity(
                id = "act-orphan",
                name = "Never wired in",
                sourceIds = sources,
            ),
        )
        val issue =
            validator.validate(contract).issues.single { it.code == ContractValidationCode.ELEMENT_UNREACHABLE_FROM_START }
        assertEquals("act-orphan", issue.targetId)
    }

    @Test
    fun `V8 - a declared activity with no path to any end state fails ELEMENT_CANNOT_REACH_END_STATE`() {
        val linear = linearContract()
        val contract = linear.copy(
            activities = linear.activities + ContractActivity(
                id = "act-dead-end",
                name = "Leads nowhere",
                sourceIds = sources,
            ),
            flows = linear.flows + ContractFlow.Sequence(from = "activity-review", to = "act-dead-end"),
        )
        assertTrue(
            validator.validate(contract).issues.any {
                it.code == ContractValidationCode.ELEMENT_CANNOT_REACH_END_STATE && it.targetId == "act-dead-end"
            },
        )
    }

    @Test
    fun `V9 - a decision branch with no realising flow fails DECISION_BRANCH_NOT_REALIZED`() {
        val branching = branchingContract()
        val decision = branching.decisions.single()
        val extraBranch = ConditionalBranch(id = "branch-extra", label = "Extra", condition = "x")
        val contract = branching.copy(decisions = listOf(decision.copy(branches = decision.branches + extraBranch)))
        val issue =
            validator.validate(contract).issues.single { it.code == ContractValidationCode.DECISION_BRANCH_NOT_REALIZED }
        assertEquals("branch-extra", issue.targetId)
    }

    @Test
    fun `V10 - a Sequence flow from a decision fails SEQUENCE_FLOW_FROM_DECISION`() {
        val branching = branchingContract()
        val contract = branching.copy(
            flows = branching.flows + ContractFlow.Sequence(from = "decision-eligible", to = "end-approved"),
        )
        assertTrue(
            validator.validate(contract).issues.any { it.code == ContractValidationCode.SEQUENCE_FLOW_FROM_DECISION },
        )
    }

    @Test
    fun `V11 - a flow reaching a subprocess member directly fails FLOW_CROSSES_SUBPROCESS_BOUNDARY`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + ContractActivity.SubProcess(
                id = "sub-assess",
                name = "Assess",
                containedActivityIds = listOf("activity-review"),
                sourceIds = sources,
            ),
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "activity-receive"),
                ContractFlow.Sequence(from = "activity-receive", to = "activity-review"),
                ContractFlow.Sequence(from = "activity-review", to = "end-approved"),
            ),
        )
        assertTrue(
            validator.validate(contract).issues.any { it.code == ContractValidationCode.FLOW_CROSSES_SUBPROCESS_BOUNDARY },
        )
    }

    @Test
    fun `V12 - a subprocess member unreachable from any entry point fails SUBPROCESS_MEMBER_UNREACHABLE`() {
        // A closed 2-cycle with no edge in from the subprocess's real entry point: neither
        // act-cycle-a nor act-cycle-b has-no-internal-predecessor (each is the other's
        // predecessor), so neither counts as an entry point, and both are unreachable from the
        // one that does (activity-receive).
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + listOf(
                ContractActivity(id = "act-cycle-a", name = "Cycle A", sourceIds = sources),
                ContractActivity(id = "act-cycle-b", name = "Cycle B", sourceIds = sources),
                ContractActivity.SubProcess(
                    id = "sub-assess",
                    name = "Assess",
                    containedActivityIds = listOf("activity-receive", "activity-review", "act-cycle-a", "act-cycle-b"),
                    sourceIds = sources,
                ),
            ),
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "sub-assess"),
                ContractFlow.Sequence(from = "activity-receive", to = "activity-review"),
                ContractFlow.Sequence(from = "act-cycle-a", to = "act-cycle-b"),
                ContractFlow.Sequence(from = "act-cycle-b", to = "act-cycle-a"),
                ContractFlow.Sequence(from = "sub-assess", to = "end-approved"),
            ),
        )
        val report = validator.validate(contract)
        val unreachable = report.issues.filter { it.code == ContractValidationCode.SUBPROCESS_MEMBER_UNREACHABLE }
        assertEquals(setOf("act-cycle-a", "act-cycle-b"), unreachable.map { it.targetId }.toSet())
    }

    @Test
    fun `V13 - a decision with two branches to the same target passes (per-kind uniqueness)`() {
        val branching = branchingContract()
        val decision = branching.decisions.single()
        val secondBranch = ConditionalBranch(id = "branch-also-approved", label = "Also approved", condition = "y")
        val contract = branching.copy(
            decisions = listOf(decision.copy(branches = decision.branches + secondBranch)),
            flows = branching.flows + ContractFlow.Branch(
                from = "decision-eligible",
                to = "end-approved",
                branchId = "branch-also-approved",
            ),
        )
        assertFalse(
            validator.validate(contract).issues.any {
                it.code == ContractValidationCode.DUPLICATE_FLOW || it.code == ContractValidationCode.DUPLICATE_BRANCH_REALIZATION
            },
        )
    }

    @Test
    fun `V13 - two identical Sequence flows fail DUPLICATE_FLOW`() {
        val linear = linearContract()
        val contract = linear.copy(
            flows = linear.flows + ContractFlow.Sequence(from = "activity-receive", to = "activity-review"),
        )
        assertTrue(validator.validate(contract).issues.any { it.code == ContractValidationCode.DUPLICATE_FLOW })
    }

    // A boundary event inherits its host's position in the graph: reached when the host is
    // reached, contained where the host is contained, with only its degree its own.

    @Test
    fun `a contract with a boundary event on a reachable host is valid`() {
        val contract = boundaryEventContract()
        val report = validator.validate(contract)
        assertTrue(report.isValid, "expected boundary event contract to be valid, got ${report.issues}")
    }

    @Test
    fun `the handler path behind a boundary event is not reported unreachable`() {
        val report = validator.validate(boundaryEventContract())
        val unreachable = report.issues.filter { it.code == ContractValidationCode.ELEMENT_UNREACHABLE_FROM_START }
        assertTrue(unreachable.isEmpty(), "expected no unreachable elements, got $unreachable")
    }

    @Test
    fun `a boundary event on an unreachable host is still reported`() {
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + ContractActivity.Service(
                id = "act-orphan",
                name = "Orphan activity",
                sourceIds = sources,
                modifiers = ActivityModifiers(
                    boundaryEvents = listOf(
                        ContractBoundaryEvent(
                            kind = BoundaryEventKind.TIMER,
                            label = "Timeout",
                            detail = "PT1H",
                            id = "be-orphan-timeout",
                        ),
                    ),
                ),
            ),
            // act-orphan has no incoming flow — its host, and therefore its boundary event, is
            // unreachable. The fix must not excuse either.
            flows = base.flows + ContractFlow.Sequence(from = "be-orphan-timeout", to = "end-approved"),
        )
        val unreachable = validator.validate(contract).issues
            .filter { it.code == ContractValidationCode.ELEMENT_UNREACHABLE_FROM_START }
            .map { it.targetId }
        assertTrue("act-orphan" in unreachable, "expected the unreachable host to be reported: $unreachable")
        assertTrue("be-orphan-timeout" in unreachable, "expected the boundary event to still be reported: $unreachable")
    }

    @Test
    fun `a host whose own path dead-ends still fails V8 despite a live boundary handler`() {
        // act-deadend's own outgoing path leads only to act-stuck, which has no outgoing flow — a
        // real dead end. Its boundary event's handler DOES reach an end. V8 must not let that
        // attachment excuse the host: inAdjacency walks flows only, never attachment edges.
        val base = linearContract()
        val contract = base.copy(
            activities = base.activities + listOf(
                ContractActivity.Service(
                    id = "act-deadend",
                    name = "Dead end activity",
                    sourceIds = sources,
                    modifiers = ActivityModifiers(
                        boundaryEvents = listOf(
                            ContractBoundaryEvent(
                                kind = BoundaryEventKind.ERROR,
                                label = "Processing failed",
                                detail = "PROCESSING_FAILED",
                                id = "be-deadend-error",
                            ),
                        ),
                    ),
                ),
                ContractActivity(id = "act-stuck", name = "Stuck activity", sourceIds = sources),
                ContractActivity(id = "act-handle-deadend-error", name = "Handle failure", sourceIds = sources),
            ),
            flows = base.flows + listOf(
                ContractFlow.Sequence(from = "activity-receive", to = "act-deadend"),
                ContractFlow.Sequence(from = "act-deadend", to = "act-stuck"),
                ContractFlow.Sequence(from = "be-deadend-error", to = "act-handle-deadend-error"),
                ContractFlow.Sequence(from = "act-handle-deadend-error", to = "end-approved"),
            ),
        )
        val cannotReachEnd = validator.validate(contract).issues
            .filter { it.code == ContractValidationCode.ELEMENT_CANNOT_REACH_END_STATE }
            .map { it.targetId }
        assertTrue(
            "act-deadend" in cannotReachEnd,
            "attachment must not let a dead-end host pass V8 via its boundary handler: $cannotReachEnd",
        )
    }

    @Test
    fun `V11 - a boundary event on a subprocess member routed to a sibling member passes`() {
        assertFalse(
            validator.validate(subprocessMemberBoundaryEventContract(routeOutsideSubprocess = false)).issues.any {
                it.code == ContractValidationCode.FLOW_CROSSES_SUBPROCESS_BOUNDARY
            },
        )
    }

    @Test
    fun `V11 - a boundary event on a subprocess member routed outside the subprocess fails`() {
        assertTrue(
            validator.validate(subprocessMemberBoundaryEventContract(routeOutsideSubprocess = true)).issues.any {
                it.code == ContractValidationCode.FLOW_CROSSES_SUBPROCESS_BOUNDARY
            },
        )
    }

    // The repair a diagnostic names must be one its target can perform. A boundary event is
    // reached by attachment rather than by a flow, so it has no edge to reroute through the
    // subprocess's own id.
    @Test
    fun `V11 - the crossing diagnostic prescribes a repair the offending element can perform`() {
        val fromBoundaryEvent = validator.validate(subprocessMemberBoundaryEventContract(routeOutsideSubprocess = true))
            .issues.single { it.code == ContractValidationCode.FLOW_CROSSES_SUBPROCESS_BOUNDARY }
        assertTrue(
            "attach the boundary event to the subprocess itself" in fromBoundaryEvent.message,
            "a boundary event must be told a repair it can perform; got: ${fromBoundaryEvent.message}",
        )
        assertFalse(
            "route through the subprocess's own id" in fromBoundaryEvent.message,
            "a boundary event has no edge to reroute; got: ${fromBoundaryEvent.message}",
        )

        // An ordinary activity-to-activity crossing keeps the original advice, which is correct
        // for it — the discrimination must not blanket-replace the message.
        val base = linearContract()
        val ordinaryCrossing = base.copy(
            activities = base.activities + ContractActivity.SubProcess(
                id = "sub-assess",
                name = "Assess",
                containedActivityIds = listOf("activity-review"),
                sourceIds = sources,
            ),
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "activity-receive"),
                ContractFlow.Sequence(from = "activity-receive", to = "activity-review"),
                ContractFlow.Sequence(from = "activity-review", to = "end-approved"),
            ),
        )
        val fromActivity = validator.validate(ordinaryCrossing).issues
            .first { it.code == ContractValidationCode.FLOW_CROSSES_SUBPROCESS_BOUNDARY }
        assertTrue(
            "route through the subprocess's own id" in fromActivity.message,
            "an ordinary edge keeps the reroute advice; got: ${fromActivity.message}",
        )
    }

    @Test
    fun `V12 - a subprocess member reachable only via a sibling's boundary event is not unreachable`() {
        val contract = subprocessMemberBoundaryEventContract(routeOutsideSubprocess = false)
        val unreachable = validator.validate(contract).issues
            .filter { it.code == ContractValidationCode.SUBPROCESS_MEMBER_UNREACHABLE }
            .map { it.targetId }
        assertTrue("act-followup" !in unreachable, "expected act-followup reachable via attachment: $unreachable")
    }

    @Test
    fun `V12 - a member cycle with no entry point is still unreachable`() {
        // A closed 2-cycle has no member with zero internal predecessors, so neither of its two
        // members counts as an entry point (mirrors the existing V12 test's cycle case) — the
        // attachment seeding must not accidentally make this pair reachable.
        val base = subprocessMemberBoundaryEventContract(routeOutsideSubprocess = false)
        val subAssess = base.activities.single { it.id == "sub-assess" } as ContractActivity.SubProcess
        val contract = base.copy(
            activities = base.activities.map { activity ->
                if (activity.id == "sub-assess") {
                    subAssess.copy(
                        containedActivityIds = subAssess.containedActivityIds + listOf("act-stranded-a", "act-stranded-b"),
                    )
                } else {
                    activity
                }
            } + listOf(
                ContractActivity(id = "act-stranded-a", name = "Stranded A", sourceIds = sources),
                ContractActivity(id = "act-stranded-b", name = "Stranded B", sourceIds = sources),
            ),
            flows = base.flows + listOf(
                ContractFlow.Sequence(from = "act-stranded-a", to = "act-stranded-b"),
                ContractFlow.Sequence(from = "act-stranded-b", to = "act-stranded-a"),
            ),
        )
        val unreachable = validator.validate(contract).issues
            .filter { it.code == ContractValidationCode.SUBPROCESS_MEMBER_UNREACHABLE }
            .map { it.targetId }
        assertEquals(setOf("act-stranded-a", "act-stranded-b"), unreachable.toSet())
    }

    @Test
    fun `V5 - a boundary event with an incoming flow fails`() {
        val base = boundaryEventContract()
        val contract = base.copy(
            flows = base.flows + ContractFlow.Sequence(from = "act-handle-timeout", to = "be-charge-timeout"),
        )
        assertTrue(
            validator.validate(contract).issues.any {
                it.code == ContractValidationCode.BOUNDARY_EVENT_HAS_INCOMING_FLOW
            },
        )
    }

    @Test
    fun `V5 - a boundary event with two outgoing flows fails`() {
        val base = boundaryEventContract()
        val contract = base.copy(
            flows = base.flows + ContractFlow.Sequence(from = "be-charge-timeout", to = "end-timeout-handled"),
        )
        val issue = validator.validate(contract).issues
            .single { it.code == ContractValidationCode.BOUNDARY_EVENT_OUTGOING_COUNT_WRONG }
        assertEquals("be-charge-timeout", issue.targetId)
    }

    private fun linearContract(): ProcessContract = ProcessContract(
        id = "contract-linear",
        processName = "Submit application",
        summary = "Application is submitted and reviewed.",
        start = ContractStart(ContractTrigger.None("Applicant submits an application"), sources),
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
        flows = listOf(
            ContractFlow.Sequence(from = "start", to = "activity-receive"),
            ContractFlow.Sequence(from = "activity-receive", to = "activity-review"),
            ContractFlow.Sequence(from = "activity-review", to = "end-approved"),
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
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "activity-receive"),
                ContractFlow.Sequence(from = "activity-receive", to = "decision-eligible"),
                ContractFlow.Branch(from = "decision-eligible", to = "activity-review", branchId = "branch-yes"),
                ContractFlow.Branch(from = "decision-eligible", to = "end-approved", branchId = "branch-no"),
                ContractFlow.Sequence(from = "activity-review", to = "end-approved"),
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
            // The "not eligible" branch routes to the new rejection end state instead of
            // end-approved — this is the exception path the fixture name promises.
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "activity-receive"),
                ContractFlow.Sequence(from = "activity-receive", to = "decision-eligible"),
                ContractFlow.Branch(from = "decision-eligible", to = "activity-review", branchId = "branch-yes"),
                ContractFlow.Branch(from = "decision-eligible", to = "end-rejected", branchId = "branch-no"),
                ContractFlow.Sequence(from = "activity-review", to = "end-approved"),
            ),
        )
    }

    // Positive fixture: exercises every element kind `flowAddressableIds()`
    // enumerates (start, activity, decision, end state, intermediate throw, boundary event) plus
    // a subprocess, all satisfying V1-V13 at once. A boundary event on act-charge routes through
    // an intermediate throw to its own handler and end state; a decision branches into a
    // subprocess or straight to a failure end state.
    // act-charge carries the boundary event; extracted so boundaryEventContract() stays under
    // detekt's LongMethod limit.
    private fun chargeActivityWithTimeoutBoundary(): ContractActivity = ContractActivity.Service(
        id = "act-charge",
        name = "Charge payment",
        sourceIds = sources,
        modifiers = ActivityModifiers(
            boundaryEvents = listOf(
                ContractBoundaryEvent(
                    kind = BoundaryEventKind.TIMER,
                    label = "24h timeout",
                    detail = "PT24H",
                    id = "be-charge-timeout",
                ),
            ),
        ),
    )

    private fun boundaryEventContract(): ProcessContract = ProcessContract(
        id = "contract-boundary-event",
        processName = "Charge payment with timeout escalation",
        summary = "A payment is charged; a stalled charge escalates while fulfilment proceeds separately.",
        start = ContractStart(ContractTrigger.None("A payment charge is requested"), sources),
        activities = listOf(
            chargeActivityWithTimeoutBoundary(),
            ContractActivity(id = "act-handle-timeout", name = "Handle timeout", sourceIds = sources),
            ContractActivity.SubProcess(
                id = "sub-fulfil",
                name = "Fulfil order",
                containedActivityIds = listOf("act-pack", "act-ship"),
                sourceIds = sources,
            ),
            ContractActivity(id = "act-pack", name = "Pack order", sourceIds = sources),
            ContractActivity(id = "act-ship", name = "Ship order", sourceIds = sources),
        ),
        decisions = listOf(
            ContractDecision(
                id = "dec-charge-outcome",
                question = "Did the charge succeed?",
                branches = listOf(
                    ConditionalBranch(id = "branch-charged", label = "Charged", condition = "charge succeeded"),
                    ConditionalBranch(id = "branch-failed", label = "Failed", condition = "charge failed"),
                ),
                sourceIds = sources,
            ),
        ),
        endStates = listOf(
            ContractEndState(id = "end-fulfilled", name = "Order fulfilled", sourceIds = sources),
            ContractEndState(id = "end-timeout-handled", name = "Timeout handled", sourceIds = sources),
            ContractEndState(id = "end-failed", name = "Charge failed", sourceIds = sources),
        ),
        intermediateThrows = listOf(
            ContractIntermediateThrow.Signal(
                id = "throw-notify",
                name = "Notify operations",
                signalName = "charge-timeout",
                sourceIds = sources,
            ),
        ),
        flows = listOf(
            ContractFlow.Sequence(from = "start", to = "act-charge"),
            ContractFlow.Sequence(from = "act-charge", to = "dec-charge-outcome"),
            ContractFlow.Branch(from = "dec-charge-outcome", to = "sub-fulfil", branchId = "branch-charged"),
            ContractFlow.Branch(from = "dec-charge-outcome", to = "end-failed", branchId = "branch-failed"),
            ContractFlow.Sequence(from = "sub-fulfil", to = "end-fulfilled"),
            ContractFlow.Sequence(from = "act-pack", to = "act-ship"),
            ContractFlow.Sequence(from = "be-charge-timeout", to = "throw-notify"),
            ContractFlow.Sequence(from = "throw-notify", to = "act-handle-timeout"),
            ContractFlow.Sequence(from = "act-handle-timeout", to = "end-timeout-handled"),
        ),
    )

    // Subprocess "sub-assess" contains activity-review (with a boundary event) and act-followup.
    // `routeOutsideSubprocess = false` routes the handler to its sibling member (valid: V11
    // passes, V12's member closure makes act-followup reachable via attachment); `true` routes it
    // to the top-level end state instead (invalid: crosses the subprocess boundary).
    private fun subprocessMemberBoundaryEventContract(routeOutsideSubprocess: Boolean): ProcessContract {
        val base = linearContract()
        val review = base.activities.single { it.id == "activity-review" } as ContractActivity.Service
        return base.copy(
            activities = base.activities.map { activity ->
                if (activity.id == "activity-review") {
                    review.copy(
                        modifiers = ActivityModifiers(
                            boundaryEvents = listOf(
                                ContractBoundaryEvent(
                                    kind = BoundaryEventKind.ERROR,
                                    label = "Review failed",
                                    detail = "REVIEW_FAILED",
                                    id = "be-review-failed",
                                ),
                            ),
                        ),
                    )
                } else {
                    activity
                }
            } + listOf(
                ContractActivity(id = "act-followup", name = "Follow up on failure", sourceIds = sources),
                ContractActivity.SubProcess(
                    id = "sub-assess",
                    name = "Assess",
                    containedActivityIds = listOf("activity-review", "act-followup"),
                    sourceIds = sources,
                ),
            ),
            // The outer flow enters and exits through sub-assess's own id (V11) — activity-review
            // is the subprocess's internal entry point, with no incoming flow of its own (V12).
            // Members are excluded from V6, so act-followup needs no further outgoing flow.
            flows = listOf(
                ContractFlow.Sequence(from = "start", to = "activity-receive"),
                ContractFlow.Sequence(from = "activity-receive", to = "sub-assess"),
                ContractFlow.Sequence(from = "sub-assess", to = "end-approved"),
                if (routeOutsideSubprocess) {
                    ContractFlow.Sequence(from = "be-review-failed", to = "end-approved")
                } else {
                    ContractFlow.Sequence(from = "be-review-failed", to = "act-followup")
                },
            ),
        )
    }
}
