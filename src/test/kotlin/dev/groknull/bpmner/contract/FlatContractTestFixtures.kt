/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatActivityKind
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractActivity
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractEndState
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractStart
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatContractTrigger
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatEndStateKind
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatProcessContract
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatTriggerKind

/**
 * Published test fixture for the `contract` module's flat wire-format types.
 *
 * These types live in `contract.internal.adapter.inbound` (the LLM adapter boundary),
 * and are legitimately accessed here because this fixture is in the `contract` module's
 * test scope (same-module reach). Cross-module test callers import only this object
 * from the `contract` root, never reaching into the internal package directly.
 *
 * (S5 — ARCHITECTURE §5 S5, §1.5; cross-module test fixture published at module root)
 */
object FlatContractTestFixtures {
    /**
     * The runtime class of [FlatProcessContract], exposed for use in `whenCreateObject`
     * mocking without requiring callers to import the internal type.
     */
    @JvmField
    val FLAT_PROCESS_CONTRACT_CLASS: Class<*> = FlatProcessContract::class.java

    /**
     * A minimal valid [FlatProcessContract] suitable for mocking LLM output in e2e/integration
     * tests. Returns [Any] so callers need not import the internal type.
     */
    @JvmStatic
    fun minimalContract(
        id: String = "contract-1",
        processName: String = "Dummy",
        summary: String = "Summary",
    ): Any = FlatProcessContract(
        id = id,
        processName = processName,
        summary = summary,
        start = FlatContractStart(
            trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Trigger"),
            sourceIds = listOf("ev1"),
        ),
        activities = listOf(
            FlatContractActivity(id = "a1", name = "A1", kind = FlatActivityKind.SERVICE, sourceIds = listOf("ev1")),
            FlatContractActivity(id = "a2", name = "A2", kind = FlatActivityKind.SERVICE, sourceIds = listOf("ev1")),
        ),
        endStates = listOf(
            FlatContractEndState(id = "e1", name = "E1", kind = FlatEndStateKind.NORMAL, sourceIds = listOf("ev1")),
        ),
    )

    /**
     * A [FlatProcessContract] representing a "Make Toast" process.
     * Returns [Any] so callers need not import the internal type.
     */
    @JvmStatic
    fun makeToastContract(): Any {
        val sources = listOf("s1")
        return FlatProcessContract(
            id = "contract-1",
            processName = "Make Toast",
            summary = "Toast making process",
            start = FlatContractStart(
                trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Hunger"),
                sourceIds = sources,
            ),
            activities = listOf(
                FlatContractActivity(id = "a1", name = "Get bread", kind = FlatActivityKind.SERVICE, sourceIds = sources),
                FlatContractActivity(id = "a2", name = "Toast bread", kind = FlatActivityKind.SERVICE, sourceIds = sources),
            ),
            endStates = listOf(
                FlatContractEndState(id = "e1", name = "Toast ready", kind = FlatEndStateKind.NORMAL, sourceIds = sources),
            ),
        )
    }

    /**
     * A [FlatProcessContract] for the canonical credit-application process used in prompt
     * probes and pipeline tests. Returns [Any] so callers need not import the internal type.
     */
    @JvmStatic
    fun canonicalCreditContract(): Any = FlatProcessContract(
        id = "contract-credit-application",
        processName = "Credit application",
        summary = "Credit applications are scored and approved automatically when the score is high enough.",
        start = FlatContractStart(
            trigger = FlatContractTrigger(type = FlatTriggerKind.NONE, description = "Customer submits credit application"),
            sourceIds = listOf("ev1"),
        ),
        activities = listOf(
            FlatContractActivity(
                id = "act-run-credit-check",
                name = "Run credit check",
                kind = FlatActivityKind.SERVICE,
                sourceIds = listOf("ev1"),
            ),
            FlatContractActivity(
                id = "act-underwriter-review",
                name = "Review credit application",
                kind = FlatActivityKind.USER,
                sourceIds = listOf("ev2"),
            ),
        ),
        endStates = listOf(
            FlatContractEndState(
                id = "end-approved",
                name = "Application approved",
                kind = FlatEndStateKind.NORMAL,
                sourceIds = listOf("ev2"),
            ),
            FlatContractEndState(
                id = "end-reviewed",
                name = "Application reviewed",
                kind = FlatEndStateKind.NORMAL,
                sourceIds = listOf("ev1"),
            ),
        ),
    )
}
