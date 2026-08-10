/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.bpmn.BoundaryEventKind
import dev.groknull.bpmner.contract.ActivityModifiers
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractBoundaryEvent
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractLoop
import dev.groknull.bpmner.contract.ProcessContract
import kotlin.test.Test
import kotlin.test.assertTrue

class ContractConservationTest {
    private val sources = listOf("ev1")

    // Mirrors Run B's actual regression: a retry driven by a diagnostic naming one decision
    // dropped boundary events and loops from three unrelated activities. That regression passed
    // contract validation, so R5 must catch it here, before the loop's success return.
    @Test
    fun `attempt 2 dropping unrelated activities' modifiers is rejected and names all of them`() {
        val previous = regressionContract()
        val next = previous.copy(
            activities = previous.activities.map { activity ->
                if (activity.id == "dec-timeout") {
                    activity
                } else {
                    (activity as ContractActivity.Service).copy(modifiers = ActivityModifiers())
                }
            },
        )

        val drops = ContractConservation.detectDrops(named = setOf("dec-timeout"), previous = previous, next = next)

        assertTrue(drops.any { it.contains("act-hold-payment-failed") })
        assertTrue(drops.any { it.contains("act-show-retry-prompt") })
        assertTrue(drops.any { it.contains("act-notify-ops") })
    }

    @Test
    fun `attempt 2 restructuring only the named decision's branches is accepted`() {
        val previous = regressionContract()
        val decision = previous.decisions.single { it.id == "dec-timeout" }
        val next = previous.copy(
            decisions = listOf(
                decision.copy(
                    branches = listOf(
                        ConditionalBranch(id = "branch-new", label = "Restructured", condition = "x"),
                    ),
                ),
            ),
        )

        val drops = ContractConservation.detectDrops(named = setOf("dec-timeout"), previous = previous, next = next)

        assertTrue(drops.isEmpty(), "got: $drops")
    }

    @Test
    fun `a retry driven only by a process-level diagnostic rejects any drop at all`() {
        // named = ∅: no element id was blamed, so nothing is licensed to change.
        val previous = regressionContract()
        val next = previous.copy(activities = previous.activities.drop(1))

        val drops = ContractConservation.detectDrops(named = emptySet(), previous = previous, next = next)

        assertTrue(drops.isNotEmpty())
    }

    @Test
    fun `a partial boundary-event removal is caught even though the list stays non-empty`() {
        // act-show-retry-prompt keeps its TIMER boundary event but drops a second, ERROR one —
        // the list stays non-empty on both sides, so a whole-list presence check would miss this.
        val previous = regressionContract()
        val withTwoBoundaryEvents = previous.copy(
            activities = previous.activities.map { activity ->
                if (activity.id == "act-show-retry-prompt") {
                    (activity as ContractActivity.Service).copy(
                        modifiers = activity.modifiers.copy(
                            boundaryEvents = activity.modifiers.boundaryEvents + ContractBoundaryEvent(
                                kind = BoundaryEventKind.ERROR,
                                label = "Payment gateway error",
                                nextRef = "end-abandoned",
                            ),
                        ),
                    )
                } else {
                    activity
                }
            },
        )
        val next = withTwoBoundaryEvents.copy(
            activities = withTwoBoundaryEvents.activities.map { activity ->
                if (activity.id == "act-show-retry-prompt") {
                    (activity as ContractActivity.Service).copy(
                        modifiers = activity.modifiers.copy(
                            boundaryEvents = activity.modifiers.boundaryEvents.filter { it.kind == BoundaryEventKind.TIMER },
                        ),
                    )
                } else {
                    activity
                }
            },
        )

        val drops = ContractConservation.detectDrops(named = setOf("dec-timeout"), previous = withTwoBoundaryEvents, next = next)

        assertTrue(
            drops.any { it.contains("act-show-retry-prompt") && it.contains("ERROR") },
            "got: $drops",
        )
    }

    @Test
    fun `attempt 1 is a no-op`() {
        val contract = regressionContract()
        val drops = ContractConservation.detectDrops(named = emptySet(), previous = contract, next = contract)
        assertTrue(drops.isEmpty())
    }

    private fun regressionContract(): ProcessContract = ProcessContract(
        id = "c-regression",
        processName = "Payment retry",
        summary = "Retry a failed payment with a timeout escape.",
        trigger = "Payment attempted",
        triggerSourceIds = sources,
        activities = listOf(
            ContractActivity.Service(
                id = "act-hold-payment-failed",
                name = "Hold payment failed",
                sourceIds = sources,
                modifiers = ActivityModifiers(loop = ContractLoop(loopCondition = "not yet successful")),
            ),
            ContractActivity.Service(
                id = "act-show-retry-prompt",
                name = "Show retry prompt",
                sourceIds = sources,
                modifiers = ActivityModifiers(
                    boundaryEvents = listOf(
                        ContractBoundaryEvent(
                            kind = BoundaryEventKind.TIMER,
                            label = "24h timeout",
                            nextRef = "end-abandoned",
                        ),
                    ),
                ),
            ),
            ContractActivity.Service(
                id = "act-notify-ops",
                name = "Notify ops",
                sourceIds = sources,
                modifiers = ActivityModifiers(
                    boundaryEvents = listOf(
                        ContractBoundaryEvent(
                            kind = BoundaryEventKind.ERROR,
                            label = "Notify failure",
                            nextRef = "end-abandoned",
                        ),
                    ),
                ),
            ),
        ),
        decisions = listOf(
            ContractDecision(
                id = "dec-timeout",
                question = "Did the retry time out?",
                branches = listOf(
                    ConditionalBranch(id = "branch-timeout", label = "Timed out", condition = "elapsed > 24h"),
                    ConditionalBranch(id = "branch-ok", label = "Retried", condition = "elapsed <= 24h"),
                ),
                sourceIds = sources,
            ),
        ),
        endStates = listOf(ContractEndState(id = "end-abandoned", name = "Abandoned", sourceIds = sources)),
    )
}
