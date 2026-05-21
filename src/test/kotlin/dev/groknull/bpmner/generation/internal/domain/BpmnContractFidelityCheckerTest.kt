/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.generation.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.UnconditionalBranch
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnEndEvent
import dev.groknull.bpmner.core.BpmnEventDefinition
import dev.groknull.bpmner.core.BpmnExclusiveGateway
import dev.groknull.bpmner.core.BpmnNoneEventDefinition
import dev.groknull.bpmner.core.BpmnParallelGateway
import dev.groknull.bpmner.core.BpmnStartEvent
import dev.groknull.bpmner.core.BpmnTerminateEventDefinition
import dev.groknull.bpmner.core.BpmnUserTask
import dev.groknull.bpmner.generation.BpmnFidelityCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Convention exercised by every fixture below: contract element ids (`act-…`, `dec-…`, `end-…`)
 * are used verbatim as BPMN node ids. Element kind is carried by `BpmnNode.type`. See `BpmnDomain.kt`
 * and `BpmnContractGenerationPromptFactory.kt` for the canonical generator instructions.
 */
class BpmnContractFidelityCheckerTest {
    private val checker = BpmnContractFidelityChecker()

    @Test
    fun `valid loop with back-edge passes`() {
        val report = checker.check(repairLoopContract(), repairLoopDefinitionWithBackEdge())

        assertTrue(report.isValid, "expected valid report, got: ${report.issues}")
    }

    @Test
    fun `missing branch flow flagged as BRANCH_FLOW_MISSING`() {
        // Definition lacks the back-edge from dec-validate to act-strategy-1
        val report = checker.check(repairLoopContract(), repairLoopDefinitionFlattened())

        assertFalse(report.isValid)
        assertTrue(
            report.issues.any { it.code == BpmnFidelityCode.BRANCH_FLOW_MISSING },
            "expected BRANCH_FLOW_MISSING; got: ${report.issues.map { it.code }}",
        )
    }

    @Test
    fun `branch flow through unnamed converging exclusive join passes`() {
        val report =
            checker.check(
                skipForwardContract(),
                skipForwardViaJoinDefinition(join = BpmnExclusiveGateway("Gateway_join", name = null)),
            )
        assertTrue(
            report.isValid,
            "transparent exclusive join should not trip BRANCH_FLOW_MISSING; got: ${report.issues}",
        )
    }

    @Test
    fun `branch flow through unnamed converging parallel join passes`() {
        val report =
            checker.check(
                skipForwardContract(),
                skipForwardViaJoinDefinition(join = BpmnParallelGateway("Gateway_join", name = null)),
            )
        assertTrue(
            report.isValid,
            "transparent parallel join should not trip BRANCH_FLOW_MISSING; got: ${report.issues}",
        )
    }

    @Test
    fun `branch flow through named gateway still flagged as BRANCH_FLOW_MISSING`() {
        // A named gateway carries semantic content — it's not transparent. The walk must stop there.
        val report =
            checker.check(
                skipForwardContract(),
                skipForwardViaJoinDefinition(join = BpmnExclusiveGateway("Gateway_named", name = "Re-check?")),
            )
        assertFalse(report.isValid)
        assertTrue(
            report.issues.any { it.code == BpmnFidelityCode.BRANCH_FLOW_MISSING },
            "expected BRANCH_FLOW_MISSING for named intermediate gateway; got: ${report.issues.map { it.code }}",
        )
    }

    @Test
    fun `branch flow through gateway with multiple outbounds still flagged as BRANCH_FLOW_MISSING`() {
        // A converging gateway that fans out again is a fork, not a transparent merge.
        val report = checker.check(skipForwardContract(), skipForwardViaMultiOutboundJoinDefinition())
        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.BRANCH_FLOW_MISSING })
    }

    @Test
    fun `branch flow through user task still flagged as BRANCH_FLOW_MISSING`() {
        // Only gateways qualify as transparent today; tasks must never be skipped over.
        val report = checker.check(skipForwardContract(), skipForwardViaTaskDefinition())
        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.BRANCH_FLOW_MISSING })
    }

    @Test
    fun `gateway with too few outbound flows flagged as GATEWAY_BRANCH_COUNT_INSUFFICIENT`() {
        // Definition has the gateway but only one outbound (collapsed branches)
        val report = checker.check(repairLoopContract(), repairLoopDefinitionWithCollapsedBranches())

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT })
    }

    @Test
    fun `nextRef pointing at unknown node flagged as BRANCH_NEXT_REF_UNRESOLVED`() {
        val contract = unresolvedRefContract()
        val definition = unresolvedRefDefinition()

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == BpmnFidelityCode.BRANCH_NEXT_REF_UNRESOLVED })
    }

    @Test
    fun `decision without a corresponding gateway flagged as DECISION_GATEWAY_MISSING`() {
        // Contract declares dec-choose but the BPMN never emits a node with that id.
        val contract = unresolvedRefContract()
        val definitionWithoutDecisionNode = unresolvedRefDefinition()

        val report = checker.check(contract, definitionWithoutDecisionNode)

        assertTrue(
            report.issues.any { it.code == BpmnFidelityCode.DECISION_GATEWAY_MISSING },
            "expected DECISION_GATEWAY_MISSING; got: ${report.issues.map { it.code }}",
        )
    }

    @Test
    fun `activity realized as wrong task subtype flagged as ACTIVITY_TASK_KIND_MISMATCH`() {
        // Contract declares a SEND activity but the BPMN realises it as a plain UserTask —
        // the discriminator has been flattened away.
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-send",
                processName = "Notification process",
                summary = "send a notification then end",
                trigger = "start",
                triggerSourceIds = sources,
                activities =
                    listOf(
                        ContractActivity.Send(
                            id = "act-notify",
                            name = "Send decline notification",
                            messageName = "decline notification",
                            sourceIds = sources,
                        ),
                    ),
                endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Notification process",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnUserTask(id = "act-notify", name = "Send decline notification"),
                        BpmnEndEvent(id = "end-done", name = "Done"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-notify"),
                        BpmnEdge(id = "F2", sourceRef = "act-notify", targetRef = "end-done"),
                    ),
            )

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.ACTIVITY_TASK_KIND_MISMATCH &&
                    it.message.contains("kind=SEND") &&
                    it.message.contains("USER_TASK")
            },
            "expected ACTIVITY_TASK_KIND_MISMATCH citing SEND vs USER_TASK; got: ${report.issues}",
        )
    }

    @Test
    fun `end state with matching event definition passes fidelity`() {
        // Happy path: contract declares Terminate end; BPMN end event carries
        // BpmnTerminateEventDefinition. No fidelity issue should fire.
        val report =
            checker.check(
                typedEndStateContract(ContractEndState.Terminate("end-cancelled", "Cancelled")),
                typedEndStateDefinition("end-cancelled", BpmnTerminateEventDefinition),
            )
        assertTrue(report.isValid, "expected valid report for matching end-state kind; got: ${report.issues}")
    }

    @Test
    fun `end state realized as wrong event definition flagged as END_EVENT_KIND_MISMATCH`() {
        // Contract declares Terminate, BPMN realises it with NoneEventDefinition — the
        // terminate-scope semantic is lost. END_EVENT_KIND_MISMATCH should fire.
        val report =
            checker.check(
                typedEndStateContract(ContractEndState.Terminate("end-cancelled", "Cancelled")),
                typedEndStateDefinition("end-cancelled", BpmnNoneEventDefinition),
            )
        assertFalse(report.isValid)
        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.END_EVENT_KIND_MISMATCH &&
                    it.message.contains("kind=TERMINATE") &&
                    it.message.contains("BpmnNoneEventDefinition") &&
                    it.message.contains("BpmnTerminateEventDefinition")
            },
            "expected END_EVENT_KIND_MISMATCH citing TERMINATE vs None; got: ${report.issues}",
        )
    }

    @Test
    fun `end state realized as a non-end-event node flagged as END_EVENT_KIND_MISMATCH`() {
        // Contract declares Message end; BPMN realises the id as a UserTask. The check
        // should bail with END_EVENT_KIND_MISMATCH citing the expected END_EVENT shape
        // before even getting to the event-definition comparison.
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-msg",
                processName = "Confirmation process",
                summary = "send confirmation",
                trigger = "start",
                triggerSourceIds = sources,
                activities = listOf(ContractActivity.User(id = "act-do", name = "Do thing", sourceIds = sources)),
                endStates =
                    listOf(
                        ContractEndState.Message(
                            id = "end-sent",
                            name = "Confirmation sent",
                            messageName = "shipment confirmation",
                            sourceIds = sources,
                        ),
                    ),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Confirmation process",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnUserTask(id = "act-do", name = "Do thing"),
                        // The contract id end-sent is realised as a UserTask, not an EndEvent.
                        BpmnUserTask(id = "end-sent", name = "Confirmation sent"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-do"),
                        BpmnEdge(id = "F2", sourceRef = "act-do", targetRef = "end-sent"),
                    ),
            )

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.END_EVENT_KIND_MISMATCH &&
                    it.message.contains("kind=MESSAGE") &&
                    it.message.contains("USER_TASK") &&
                    it.message.contains("END_EVENT")
            },
            "expected END_EVENT_KIND_MISMATCH citing MESSAGE vs USER_TASK; got: ${report.issues}",
        )
    }

    // Compact fixture builders for the three end-state-kind tests above. Single activity,
    // single end state — keeps the test focus on the END_EVENT_KIND_MISMATCH dispatch logic.
    private fun typedEndStateContract(endState: ContractEndState): ProcessContract {
        val sources = listOf("ev1")
        return ProcessContract(
            id = "c-end",
            processName = "End-state fidelity",
            summary = "single activity then typed end",
            trigger = "start",
            triggerSourceIds = sources,
            activities = listOf(ContractActivity.User(id = "act-do", name = "Do thing", sourceIds = sources)),
            endStates =
                listOf(
                    when (endState) {
                        is ContractEndState.Normal -> endState.copy(sourceIds = sources)
                        is ContractEndState.Terminate -> endState.copy(sourceIds = sources)
                        is ContractEndState.Error -> endState.copy(sourceIds = sources)
                        is ContractEndState.Message -> endState.copy(sourceIds = sources)
                        is ContractEndState.Signal -> endState.copy(sourceIds = sources)
                        is ContractEndState.Escalation -> endState.copy(sourceIds = sources)
                    },
                ),
        )
    }

    private fun typedEndStateDefinition(
        endId: String,
        endEventDefinition: BpmnEventDefinition,
    ): BpmnDefinition =
        BpmnDefinition(
            processId = "P",
            processName = "End-state fidelity",
            nodes =
                listOf(
                    BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                    BpmnUserTask(id = "act-do", name = "Do thing"),
                    BpmnEndEvent(id = endId, name = "End", eventDefinition = endEventDefinition),
                ),
            sequences =
                listOf(
                    BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-do"),
                    BpmnEdge(id = "F2", sourceRef = "act-do", targetRef = endId),
                ),
        )

    @Test
    fun `decision realized as a non-gateway node type flagged as DECISION_GATEWAY_MISSING`() {
        val contract = repairLoopContract()
        val original = repairLoopDefinitionWithBackEdge()
        val withDecisionAsTask =
            original.copy(
                nodes =
                    original.nodes.map { node ->
                        if (node.id == "dec-validate") {
                            BpmnUserTask(id = node.id, name = node.name)
                        } else {
                            node
                        }
                    },
            )

        val report = checker.check(contract, withDecisionAsTask)

        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.DECISION_GATEWAY_MISSING && it.message.contains("USER_TASK")
            },
            "expected DECISION_GATEWAY_MISSING citing USER_TASK; got: ${report.issues}",
        )
    }

    @Test
    @Suppress("LongMethod") // inline definition fixture stays cohesive; splitting hides assertions
    fun `forward-skip branch with a real edge does NOT trigger a false back-edge flag`() {
        // Regression test for the pre-rewrite fragile heuristic: a forward-skip branch whose
        // target is realised by a real sequence flow must NOT be flagged.
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-skip",
                processName = "Forward skip",
                summary = "decision skips an intermediate activity",
                trigger = "start",
                triggerSourceIds = sources,
                activities =
                    listOf(
                        ContractActivity.User(id = "act-pre-check", name = "Pre-check", sourceIds = sources),
                        ContractActivity.User(id = "act-skip-target", name = "Process", sourceIds = sources),
                        ContractActivity.User(id = "act-detailed-path", name = "Detailed path", sourceIds = sources),
                    ),
                decisions =
                    listOf(
                        ContractDecision(
                            id = "dec-pre",
                            question = "Skip detailed path?",
                            branches =
                                listOf(
                                    ConditionalBranch(
                                        id = "br-skip",
                                        label = "Yes",
                                        condition = "skip",
                                        nextRef = "act-skip-target",
                                    ),
                                    ConditionalBranch(
                                        id = "br-detailed",
                                        label = "No",
                                        condition = "detailed",
                                        nextRef = "act-detailed-path",
                                    ),
                                ),
                            sourceIds = sources,
                        ),
                    ),
                endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Forward skip",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnUserTask(id = "act-pre-check", name = "Pre-check"),
                        BpmnExclusiveGateway(id = "dec-pre", name = "Skip detailed path?"),
                        BpmnUserTask(id = "act-skip-target", name = "Process"),
                        BpmnUserTask(id = "act-detailed-path", name = "Detailed path"),
                        BpmnEndEvent(id = "end-done", name = "Done"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-pre-check"),
                        BpmnEdge(id = "F2", sourceRef = "act-pre-check", targetRef = "dec-pre"),
                        BpmnEdge(id = "F3", sourceRef = "dec-pre", targetRef = "act-skip-target"),
                        BpmnEdge(id = "F4", sourceRef = "dec-pre", targetRef = "act-detailed-path"),
                        BpmnEdge(id = "F5", sourceRef = "act-detailed-path", targetRef = "act-skip-target"),
                        BpmnEdge(id = "F6", sourceRef = "act-skip-target", targetRef = "end-done"),
                    ),
            )

        val report = checker.check(contract, definition)

        assertTrue(report.isValid, "forward-skip must not flag any fidelity issue; got: ${report.issues}")
    }

    @Suppress("LongMethod") // explicit fixture; splitting hides the multi-decision shape
    @Test
    fun `per-decision gateway lookup catches insufficient outbound for THIS decision even when another gateway has enough`() {
        // Regression test for the pre-rewrite global-max bug: D1 has 2 branches mapped to a
        // 1-outbound gateway while D2 has 3 branches mapped to a 3-outbound gateway. The
        // checker must flag D1 even though some OTHER gateway in the BPMN has ≥2 outbound.
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "c-multi",
                processName = "Two decisions",
                summary = "multi-decision contract",
                trigger = "start",
                triggerSourceIds = sources,
                activities = listOf(ContractActivity.User(id = "act-a", name = "A", sourceIds = sources)),
                decisions =
                    listOf(
                        ContractDecision(
                            id = "dec-1",
                            question = "Q1?",
                            branches =
                                listOf(
                                    ConditionalBranch(id = "br-1a", label = "1a", condition = "1a"),
                                    ConditionalBranch(id = "br-1b", label = "1b", condition = "1b"),
                                ),
                            sourceIds = sources,
                        ),
                        ContractDecision(
                            id = "dec-2",
                            question = "Q2?",
                            branches =
                                listOf(
                                    ConditionalBranch(id = "br-2a", label = "2a", condition = "2a"),
                                    ConditionalBranch(id = "br-2b", label = "2b", condition = "2b"),
                                    ConditionalBranch(id = "br-2c", label = "2c", condition = "2c"),
                                ),
                            sourceIds = sources,
                        ),
                    ),
                endStates =
                    listOf(
                        ContractEndState(id = "end-1", name = "End 1", sourceIds = sources),
                        ContractEndState(id = "end-2", name = "End 2", sourceIds = sources),
                        ContractEndState(id = "end-3", name = "End 3", sourceIds = sources),
                    ),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Two decisions",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnUserTask(id = "act-a", name = "A"),
                        BpmnExclusiveGateway(id = "dec-1", name = "Q1?"),
                        BpmnExclusiveGateway(id = "dec-2", name = "Q2?"),
                        BpmnEndEvent(id = "end-1", name = "End 1"),
                        BpmnEndEvent(id = "end-2", name = "End 2"),
                        BpmnEndEvent(id = "end-3", name = "End 3"),
                    ),
                sequences =
                    listOf(
                        BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-a"),
                        BpmnEdge(id = "F2", sourceRef = "act-a", targetRef = "dec-1"),
                        // dec-1 has only ONE outbound (the bug case)
                        BpmnEdge(id = "F3", sourceRef = "dec-1", targetRef = "dec-2"),
                        // dec-2 has THREE outbound (the global-max distractor)
                        BpmnEdge(id = "F4", sourceRef = "dec-2", targetRef = "end-1"),
                        BpmnEdge(id = "F5", sourceRef = "dec-2", targetRef = "end-2"),
                        BpmnEdge(id = "F6", sourceRef = "dec-2", targetRef = "end-3"),
                    ),
            )

        val report = checker.check(contract, definition)

        assertFalse(report.isValid)
        val countIssues =
            report.issues.filter { it.code == BpmnFidelityCode.GATEWAY_BRANCH_COUNT_INSUFFICIENT }
        assertTrue(
            countIssues.any { it.contractElementId == "dec-1" },
            "expected GATEWAY_BRANCH_COUNT_INSUFFICIENT for dec-1; got: $countIssues",
        )
        assertTrue(
            countIssues.none { it.contractElementId == "dec-2" },
            "dec-2 has 3 outbound for 3 branches and must not be flagged; got: $countIssues",
        )
    }

    @Test
    fun `PARALLEL decision realized as BpmnParallelGateway passes`() {
        val report = checker.check(parallelForkContract(), parallelForkDefinition(useParallelFork = true))

        assertTrue(report.isValid, "expected valid report, got: ${report.issues}")
    }

    @Test
    fun `PARALLEL decision realized as BpmnExclusiveGateway flagged as DECISION_GATEWAY_KIND_MISMATCH`() {
        // Generator emitted EXCLUSIVE for the fork — the bug employee-onboarding hit. The
        // alignment LLM caught it semantically; the fidelity checker must catch it structurally.
        val report = checker.check(parallelForkContract(), parallelForkDefinition(useParallelFork = false))

        assertFalse(report.isValid)
        val mismatches = report.issues.filter { it.code == BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH }
        assertTrue(
            mismatches.any {
                it.contractElementId == "dec-prep-tracks" &&
                    it.message.contains("PARALLEL") &&
                    it.message.contains("EXCLUSIVE_GATEWAY")
            },
            "expected DECISION_GATEWAY_KIND_MISMATCH for dec-prep-tracks citing PARALLEL vs EXCLUSIVE_GATEWAY; got: $mismatches",
        )
    }

    @Test
    fun `EXCLUSIVE decision realized as BpmnParallelGateway also flagged as DECISION_GATEWAY_KIND_MISMATCH`() {
        // The symmetric error: contract was a choice but the BPMN expressed it as a parallel split.
        val contract = repairLoopContract()
        val original = repairLoopDefinitionWithBackEdge()
        val withParallelGateway =
            original.copy(
                nodes =
                    original.nodes.map { node ->
                        if (node.id == "dec-validate") {
                            BpmnParallelGateway(id = node.id, name = node.name)
                        } else {
                            node
                        }
                    },
            )

        val report = checker.check(contract, withParallelGateway)

        assertFalse(report.isValid)
        assertTrue(
            report.issues.any {
                it.code == BpmnFidelityCode.DECISION_GATEWAY_KIND_MISMATCH &&
                    it.message.contains("EXCLUSIVE") &&
                    it.message.contains("PARALLEL_GATEWAY")
            },
            "expected DECISION_GATEWAY_KIND_MISMATCH citing EXCLUSIVE vs PARALLEL_GATEWAY; got: ${report.issues}",
        )
    }

    @Test
    fun `contract without decisions returns empty report`() {
        val contract =
            ProcessContract(
                id = "c",
                processName = "Linear",
                summary = "linear",
                trigger = "start",
                triggerSourceIds = listOf("ev1"),
                activities =
                    listOf(
                        ContractActivity.User(id = "act-a", name = "A", sourceIds = listOf("ev1")),
                        ContractActivity.User(id = "act-b", name = "B", sourceIds = listOf("ev1")),
                    ),
                endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = listOf("ev1"))),
            )
        val definition =
            BpmnDefinition(
                processId = "P",
                processName = "Linear",
                nodes =
                    listOf(
                        BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                        BpmnEndEvent(id = "end-done", name = "End"),
                    ),
                sequences = listOf(BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "end-done")),
            )

        val report = checker.check(contract, definition)

        assertEquals(0, report.issues.size)
        assertTrue(report.isValid)
    }

    @Test
    fun `DefaultBranch without isDefault edge flagged as DEFAULT_FLOW_MISSING`() {
        val report = checker.check(defaultBranchContract(), defaultBranchDefinitionNoIsDefault())

        assertFalse(report.isValid)
        val dfm = report.issues.filter { it.code == BpmnFidelityCode.DEFAULT_FLOW_MISSING }
        assertEquals(1, dfm.size, "expected exactly one DEFAULT_FLOW_MISSING; got: ${report.issues}")
        assertTrue(dfm.single().message.contains("br-fallback"), "message should mention the DefaultBranch id")
        assertTrue(dfm.single().message.contains("dec-approve"), "message should mention the gateway id")
    }

    @Test
    fun `DefaultBranch with isDefault edge passes`() {
        val report = checker.check(defaultBranchContract(), defaultBranchDefinitionWithIsDefault())

        assertTrue(report.isValid, "expected valid report, got: ${report.issues}")
    }

    @Test
    fun `DefaultBranch with gateway missing entirely does NOT fire DEFAULT_FLOW_MISSING`() {
        // The gateway is absent → DECISION_GATEWAY_MISSING catches this first.
        // DEFAULT_FLOW_MISSING must not fire because `gatewayIsValid` is false.
        val report = checker.check(defaultBranchContract(), defaultBranchDefinitionNoGateway())

        assertFalse(report.isValid)
        assertTrue(
            report.issues.any { it.code == BpmnFidelityCode.DECISION_GATEWAY_MISSING },
            "expected DECISION_GATEWAY_MISSING; got: ${report.issues.map { it.code }}",
        )
        assertTrue(
            report.issues.none { it.code == BpmnFidelityCode.DEFAULT_FLOW_MISSING },
            "DEFAULT_FLOW_MISSING must not fire when gateway is missing; got: ${report.issues.map { it.code }}",
        )
    }
}

// ----- Fixtures -----

private fun repairLoopContract(): ProcessContract {
    val sources = listOf("ev1")
    return ProcessContract(
        id = "c-repair",
        processName = "Repair loop",
        summary = "Iterative repair with three exit conditions.",
        trigger = "request",
        triggerSourceIds = sources,
        activities =
            listOf(
                ContractActivity.User(id = "act-strategy-1", name = "Strategy 1", sourceIds = sources),
                ContractActivity.User(id = "act-strategy-2", name = "Strategy 2", sourceIds = sources),
                ContractActivity.User(id = "act-strategy-3", name = "Strategy 3", sourceIds = sources),
            ),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-validate",
                    question = "Did validation pass?",
                    branches =
                        listOf(
                            ConditionalBranch(id = "br-pass", label = "Pass", condition = "pass", nextRef = "end-success"),
                            ConditionalBranch(id = "br-fail", label = "Fail", condition = "fail", nextRef = "end-failed"),
                            ConditionalBranch(id = "br-retry", label = "Retry", condition = "retry", nextRef = "act-strategy-1"),
                        ),
                    sourceIds = sources,
                ),
            ),
        endStates =
            listOf(
                ContractEndState(id = "end-success", name = "Success", sourceIds = sources),
                ContractEndState(id = "end-failed", name = "Failed", sourceIds = sources),
            ),
    )
}

private fun repairLoopDefinitionWithBackEdge(): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Repair loop",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnUserTask(id = "act-strategy-1", name = "Strategy 1"),
                BpmnUserTask(id = "act-strategy-2", name = "Strategy 2"),
                BpmnUserTask(id = "act-strategy-3", name = "Strategy 3"),
                BpmnExclusiveGateway(id = "dec-validate", name = "Did validation pass?"),
                BpmnEndEvent(id = "end-success", name = "Success"),
                BpmnEndEvent(id = "end-failed", name = "Failed"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-strategy-1"),
                BpmnEdge(id = "F2", sourceRef = "act-strategy-1", targetRef = "act-strategy-2"),
                BpmnEdge(id = "F3", sourceRef = "act-strategy-2", targetRef = "act-strategy-3"),
                BpmnEdge(id = "F4", sourceRef = "act-strategy-3", targetRef = "dec-validate"),
                BpmnEdge(id = "F5", sourceRef = "dec-validate", targetRef = "end-success"),
                BpmnEdge(id = "F6", sourceRef = "dec-validate", targetRef = "end-failed"),
                BpmnEdge(id = "F7", sourceRef = "dec-validate", targetRef = "act-strategy-1"),
            ),
    )

private fun repairLoopDefinitionFlattened(): BpmnDefinition {
    val withBack = repairLoopDefinitionWithBackEdge()
    return withBack.copy(sequences = withBack.sequences.filterNot { it.id == "F7" })
}

private fun repairLoopDefinitionWithCollapsedBranches(): BpmnDefinition {
    val withBack = repairLoopDefinitionWithBackEdge()
    return withBack.copy(sequences = withBack.sequences.filterNot { it.id == "F6" || it.id == "F7" })
}

private fun parallelForkContract(): ProcessContract {
    val sources = listOf("ev1")
    return ProcessContract(
        id = "c-parallel",
        processName = "Three concurrent tracks",
        summary = "Fork into three independent preparation tracks then rejoin.",
        trigger = "Hire confirmed",
        triggerSourceIds = sources,
        activities =
            listOf(
                ContractActivity.User(id = "act-prep-it", name = "IT prep", sourceIds = sources),
                ContractActivity.User(id = "act-prep-facilities", name = "Facilities prep", sourceIds = sources),
                ContractActivity.User(id = "act-prep-manager", name = "Manager prep", sourceIds = sources),
            ),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-prep-tracks",
                    question = "Run all preparation tracks",
                    branches =
                        listOf(
                            UnconditionalBranch(id = "br-it", label = "IT", nextRef = "act-prep-it"),
                            UnconditionalBranch(id = "br-fac", label = "Facilities", nextRef = "act-prep-facilities"),
                            UnconditionalBranch(id = "br-mgr", label = "Manager", nextRef = "act-prep-manager"),
                        ),
                    kind = ContractGatewayKind.PARALLEL,
                    sourceIds = sources,
                ),
            ),
        endStates = listOf(ContractEndState(id = "end-onboarded", name = "Onboarded", sourceIds = sources)),
    )
}

private fun parallelForkDefinition(useParallelFork: Boolean): BpmnDefinition {
    val fork =
        if (useParallelFork) {
            BpmnParallelGateway(id = "dec-prep-tracks", name = "Run all preparation tracks")
        } else {
            BpmnExclusiveGateway(id = "dec-prep-tracks", name = "Run all preparation tracks")
        }
    return BpmnDefinition(
        processId = "P",
        processName = "Three concurrent tracks",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Hire confirmed"),
                fork,
                BpmnUserTask(id = "act-prep-it", name = "IT prep"),
                BpmnUserTask(id = "act-prep-facilities", name = "Facilities prep"),
                BpmnUserTask(id = "act-prep-manager", name = "Manager prep"),
                BpmnParallelGateway(id = "Gateway_join_prep", name = null),
                BpmnEndEvent(id = "end-onboarded", name = "Onboarded"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "dec-prep-tracks"),
                BpmnEdge(id = "F2", sourceRef = "dec-prep-tracks", targetRef = "act-prep-it"),
                BpmnEdge(id = "F3", sourceRef = "dec-prep-tracks", targetRef = "act-prep-facilities"),
                BpmnEdge(id = "F4", sourceRef = "dec-prep-tracks", targetRef = "act-prep-manager"),
                BpmnEdge(id = "F5", sourceRef = "act-prep-it", targetRef = "Gateway_join_prep"),
                BpmnEdge(id = "F6", sourceRef = "act-prep-facilities", targetRef = "Gateway_join_prep"),
                BpmnEdge(id = "F7", sourceRef = "act-prep-manager", targetRef = "Gateway_join_prep"),
                BpmnEdge(id = "F8", sourceRef = "Gateway_join_prep", targetRef = "end-onboarded"),
            ),
    )
}

private fun unresolvedRefContract() =
    ProcessContract(
        id = "c",
        processName = "Test",
        summary = "test",
        trigger = "start",
        triggerSourceIds = listOf("ev1"),
        activities = listOf(ContractActivity.User(id = "act-a", name = "A", sourceIds = listOf("ev1"))),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-choose",
                    question = "Choose?",
                    branches =
                        listOf(
                            ConditionalBranch(id = "br-1", label = "Option 1", condition = "1", nextRef = "act-nonexistent"),
                            DefaultBranch(id = "br-2", label = "Option 2"),
                        ),
                    sourceIds = listOf("ev1"),
                ),
            ),
        endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = listOf("ev1"))),
    )

private fun unresolvedRefDefinition() =
    BpmnDefinition(
        processId = "P",
        processName = "Test",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnUserTask(id = "act-a", name = "A"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-a"),
                BpmnEdge(id = "F2", sourceRef = "act-a", targetRef = "end-done"),
            ),
    )

/**
 * Contract for the transparent-join reachability tests.
 *
 * `dec-route` has two CONDITIONAL branches; `br-fast` resolves directly, `br-converge` goes
 * through an intermediate node before reaching its `nextRef`. Each `skipForwardVia*` fixture
 * varies the intermediate node's kind to exercise the transparency rule.
 */
private fun skipForwardContract(): ProcessContract {
    val sources = listOf("ev1")
    return ProcessContract(
        id = "c-skip",
        processName = "Skip via join",
        summary = "Two branches converge to the same downstream activity.",
        trigger = "Request received",
        triggerSourceIds = sources,
        activities =
            listOf(
                ContractActivity.User(id = "act-fast-target", name = "Fast", sourceIds = sources),
                ContractActivity.User(id = "act-converge-target", name = "Converge", sourceIds = sources),
            ),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-route",
                    question = "Which route?",
                    branches =
                        listOf(
                            ConditionalBranch(
                                id = "br-fast",
                                label = "Fast",
                                condition = "fast",
                                nextRef = "act-fast-target",
                            ),
                            ConditionalBranch(
                                id = "br-converge",
                                label = "Converge",
                                condition = "converge",
                                nextRef = "act-converge-target",
                            ),
                        ),
                    sourceIds = sources,
                ),
            ),
        endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
    )
}

/** `dec-route → [intermediate] → act-converge-target`; the intermediate node is supplied by the caller. */
private fun skipForwardViaJoinDefinition(join: dev.groknull.bpmner.core.BpmnNode): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Skip via join",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnExclusiveGateway(id = "dec-route", name = "Which route?"),
                BpmnUserTask(id = "act-fast-target", name = "Fast"),
                join,
                BpmnUserTask(id = "act-converge-target", name = "Converge"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "dec-route"),
                BpmnEdge(id = "F2", sourceRef = "dec-route", targetRef = "act-fast-target", conditionExpression = "fast"),
                BpmnEdge(id = "F3", sourceRef = "dec-route", targetRef = join.id, conditionExpression = "converge"),
                BpmnEdge(id = "F4", sourceRef = join.id, targetRef = "act-converge-target"),
                BpmnEdge(id = "F5", sourceRef = "act-fast-target", targetRef = "end-done"),
                BpmnEdge(id = "F6", sourceRef = "act-converge-target", targetRef = "end-done"),
            ),
    )

/** Variant: the intermediate is a gateway with multiple outbounds — a fork, not a transparent merge. */
private fun skipForwardViaMultiOutboundJoinDefinition(): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Skip via multi-outbound join",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnExclusiveGateway(id = "dec-route", name = "Which route?"),
                BpmnUserTask(id = "act-fast-target", name = "Fast"),
                BpmnExclusiveGateway(id = "Gateway_fork", name = null),
                BpmnUserTask(id = "act-converge-target", name = "Converge"),
                BpmnUserTask(id = "act-extra", name = "Extra"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "dec-route"),
                BpmnEdge(id = "F2", sourceRef = "dec-route", targetRef = "act-fast-target", conditionExpression = "fast"),
                BpmnEdge(id = "F3", sourceRef = "dec-route", targetRef = "Gateway_fork", conditionExpression = "converge"),
                BpmnEdge(id = "F4", sourceRef = "Gateway_fork", targetRef = "act-converge-target"),
                BpmnEdge(id = "F4b", sourceRef = "Gateway_fork", targetRef = "act-extra"),
                BpmnEdge(id = "F5", sourceRef = "act-fast-target", targetRef = "end-done"),
                BpmnEdge(id = "F6", sourceRef = "act-converge-target", targetRef = "end-done"),
                BpmnEdge(id = "F7", sourceRef = "act-extra", targetRef = "end-done"),
            ),
    )

/** Variant: the intermediate is a UserTask — never transparent. */
private fun skipForwardViaTaskDefinition(): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Skip via task",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnExclusiveGateway(id = "dec-route", name = "Which route?"),
                BpmnUserTask(id = "act-fast-target", name = "Fast"),
                BpmnUserTask(id = "Task_intermediate", name = "Intermediate work"),
                BpmnUserTask(id = "act-converge-target", name = "Converge"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "dec-route"),
                BpmnEdge(id = "F2", sourceRef = "dec-route", targetRef = "act-fast-target", conditionExpression = "fast"),
                BpmnEdge(id = "F3", sourceRef = "dec-route", targetRef = "Task_intermediate", conditionExpression = "converge"),
                BpmnEdge(id = "F4", sourceRef = "Task_intermediate", targetRef = "act-converge-target"),
                BpmnEdge(id = "F5", sourceRef = "act-fast-target", targetRef = "end-done"),
                BpmnEdge(id = "F6", sourceRef = "act-converge-target", targetRef = "end-done"),
            ),
    )

// ----- DEFAULT_FLOW_MISSING fixtures -----

/** Contract with one EXCLUSIVE decision that has a DefaultBranch. */
private fun defaultBranchContract(): ProcessContract {
    val sources = listOf("ev1")
    return ProcessContract(
        id = "c-dfm",
        processName = "Approval with fallback",
        summary = "Approve or fall back to manual review",
        trigger = "start",
        triggerSourceIds = sources,
        activities =
            listOf(
                ContractActivity.User(id = "act-review", name = "Review", sourceIds = sources),
                ContractActivity.User(id = "act-manual", name = "Manual review", sourceIds = sources),
            ),
        decisions =
            listOf(
                ContractDecision(
                    id = "dec-approve",
                    question = "Approved?",
                    branches =
                        listOf(
                            ConditionalBranch(
                                id = "br-approve",
                                label = "Approved",
                                condition = "approved",
                                nextRef = "end-done",
                            ),
                            DefaultBranch(
                                id = "br-fallback",
                                label = "Manual review",
                                nextRef = "act-manual",
                            ),
                        ),
                    sourceIds = sources,
                ),
            ),
        endStates = listOf(ContractEndState(id = "end-done", name = "Done", sourceIds = sources)),
    )
}

/** Gateway present, two outbound edges, but neither has isDefault=true. */
private fun defaultBranchDefinitionNoIsDefault(): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Approval with fallback",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnUserTask(id = "act-review", name = "Review"),
                BpmnExclusiveGateway(id = "dec-approve", name = "Approved?"),
                BpmnUserTask(id = "act-manual", name = "Manual review"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-review"),
                BpmnEdge(id = "F2", sourceRef = "act-review", targetRef = "dec-approve"),
                BpmnEdge(id = "F3", sourceRef = "dec-approve", targetRef = "end-done", conditionExpression = "approved"),
                BpmnEdge(id = "F4", sourceRef = "dec-approve", targetRef = "act-manual"),
                BpmnEdge(id = "F5", sourceRef = "act-manual", targetRef = "end-done"),
            ),
    )

/** Same as above but with isDefault=true on the fallback edge. */
private fun defaultBranchDefinitionWithIsDefault(): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Approval with fallback",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnUserTask(id = "act-review", name = "Review"),
                BpmnExclusiveGateway(id = "dec-approve", name = "Approved?"),
                BpmnUserTask(id = "act-manual", name = "Manual review"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-review"),
                BpmnEdge(id = "F2", sourceRef = "act-review", targetRef = "dec-approve"),
                BpmnEdge(id = "F3", sourceRef = "dec-approve", targetRef = "end-done", conditionExpression = "approved"),
                BpmnEdge(id = "F4", sourceRef = "dec-approve", targetRef = "act-manual", isDefault = true),
                BpmnEdge(id = "F5", sourceRef = "act-manual", targetRef = "end-done"),
            ),
    )

/** Gateway missing entirely — DECISION_GATEWAY_MISSING should fire, not DEFAULT_FLOW_MISSING. */
private fun defaultBranchDefinitionNoGateway(): BpmnDefinition =
    BpmnDefinition(
        processId = "P",
        processName = "Approval with fallback",
        nodes =
            listOf(
                BpmnStartEvent(id = "StartEvent_1", name = "Start"),
                BpmnUserTask(id = "act-review", name = "Review"),
                BpmnUserTask(id = "act-manual", name = "Manual review"),
                BpmnEndEvent(id = "end-done", name = "Done"),
            ),
        sequences =
            listOf(
                BpmnEdge(id = "F1", sourceRef = "StartEvent_1", targetRef = "act-review"),
                BpmnEdge(id = "F2", sourceRef = "act-review", targetRef = "act-manual"),
                BpmnEdge(id = "F3", sourceRef = "act-manual", targetRef = "end-done"),
            ),
    )
