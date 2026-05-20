/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractArtifact
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractGatewayKind
import dev.groknull.bpmner.contract.DefaultBranch
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.UnconditionalBranch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcessContractMarkdownRendererTest {
    private val renderer = ProcessContractMarkdownRenderer()

    @Test
    fun `renders all major sections for a full contract`() {
        val markdown = renderer.render(fullContract()).trim()

        val expected =
            """
            # Ship order
            Trigger: An order is submitted

            ## Summary
            Approved orders are packed and shipped.

            ## Actors
            - actor-warehouse: Warehouse (fulfilment)

            ## Activities
            - a-pack: Pack order (actor: actor-warehouse)
            - a-ship: Ship order (actor: actor-warehouse)

            ## Decisions
            - d-stock: Is the item in stock?
              - b-yes → "In stock" if "stock > 0"
              - b-no → "Out of stock" if "stock == 0"

            ## Artifacts
            - art-package: Package — Wrapped order ready to ship

            ## End states
            - end-shipped: Order shipped

            ## Assumptions
            - assume-payment: Payment is authorised upstream (sources: ev1)
            """.trimIndent()

        assertEquals(expected, markdown)
    }

    @Test
    @Suppress("LongMethod") // inline contract fixture stays cohesive; splitting hides assertions
    fun `renders default marker on DefaultBranch and no suffix on UnconditionalBranch`() {
        val sources = listOf("ev1")
        val contract =
            ProcessContract(
                id = "credit",
                processName = "Credit-tier routing",
                summary = "Route loan applications by credit score.",
                trigger = "Credit score returned",
                triggerSourceIds = sources,
                activities =
                    listOf(
                        ContractActivity(id = "act-fast", name = "Fast-track", sourceIds = sources),
                        ContractActivity(id = "act-manual", name = "Manual review", sourceIds = sources),
                    ),
                decisions =
                    listOf(
                        ContractDecision(
                            id = "d-tier",
                            question = "Which credit tier?",
                            branches =
                                listOf(
                                    ConditionalBranch(
                                        id = "b-fast",
                                        label = "Fast-track",
                                        condition = "score >= 750",
                                        nextRef = "act-fast",
                                    ),
                                    DefaultBranch(
                                        id = "b-manual",
                                        label = "Manual review",
                                        nextRef = "act-manual",
                                    ),
                                ),
                            sourceIds = sources,
                        ),
                        ContractDecision(
                            id = "d-prep",
                            question = "Run preparation tracks",
                            branches =
                                listOf(
                                    UnconditionalBranch(id = "b-it", label = "IT prep"),
                                    UnconditionalBranch(id = "b-fac", label = "Facilities prep"),
                                ),
                            kind = ContractGatewayKind.PARALLEL,
                            sourceIds = sources,
                        ),
                    ),
                endStates = listOf(ContractEndState(id = "end-offer", name = "Offer generated", sourceIds = sources)),
            )

        val markdown = renderer.render(contract)

        // Default branch carries the [default] marker.
        assertTrue(
            markdown.contains("- b-manual → \"Manual review\" [default] → act-manual"),
            "default branch should render the [default] marker; got:\n$markdown",
        )
        // Conditional branch keeps its existing `if "..."` rendering.
        assertTrue(
            markdown.contains("- b-fast → \"Fast-track\" if \"score >= 750\" → act-fast"),
            "conditional branch should render its condition; got:\n$markdown",
        )
        // Unconditional (parallel) branches have neither suffix.
        assertTrue(
            markdown.contains("- b-it → \"IT prep\""),
            "unconditional branch should render with no suffix; got:\n$markdown",
        )
        // The default marker must NOT appear on the unconditional branch.
        assertTrue(
            !markdown.contains("- b-it → \"IT prep\" [default]"),
            "unconditional branches must not carry the [default] marker; got:\n$markdown",
        )
    }

    @Test
    fun `omits empty sections in a minimal contract`() {
        val sources = listOf("ev1")
        val minimal =
            ProcessContract(
                id = "min",
                processName = "Approve",
                summary = "Approves things.",
                trigger = "Request arrives",
                activities =
                    listOf(
                        ContractActivity(id = "a", name = "Review", sourceIds = sources),
                        ContractActivity(id = "b", name = "Decide", sourceIds = sources),
                    ),
                endStates = listOf(ContractEndState(id = "e", name = "Done", sourceIds = sources)),
            )

        val markdown = renderer.render(minimal)

        assertTrue(markdown.contains("# Approve"))
        assertTrue(markdown.contains("## Activities"))
        assertTrue(markdown.contains("## End states"))
        assertTrue(!markdown.contains("## Actors"))
        assertTrue(!markdown.contains("## Decisions"))
        assertTrue(!markdown.contains("## Artifacts"))
        assertTrue(!markdown.contains("## Assumptions"))
    }

    @Suppress("LongMethod")
    private fun fullContract(): ProcessContract {
        val sources = listOf("ev1")
        return ProcessContract(
            id = "contract-1",
            processName = "Ship order",
            summary = "Approved orders are packed and shipped.",
            trigger = "An order is submitted",
            triggerSourceIds = sources,
            actors =
                listOf(
                    ContractActor(id = "actor-warehouse", name = "Warehouse", role = "fulfilment"),
                ),
            activities =
                listOf(
                    ContractActivity(
                        id = "a-pack",
                        name = "Pack order",
                        actorId = "actor-warehouse",
                        sourceIds = sources,
                    ),
                    ContractActivity(
                        id = "a-ship",
                        name = "Ship order",
                        actorId = "actor-warehouse",
                        sourceIds = sources,
                    ),
                ),
            decisions =
                listOf(
                    ContractDecision(
                        id = "d-stock",
                        question = "Is the item in stock?",
                        branches =
                            listOf(
                                ConditionalBranch(id = "b-yes", label = "In stock", condition = "stock > 0"),
                                ConditionalBranch(id = "b-no", label = "Out of stock", condition = "stock == 0"),
                            ),
                        sourceIds = sources,
                    ),
                ),
            artifacts =
                listOf(
                    ContractArtifact(
                        id = "art-package",
                        name = "Package",
                        description = "Wrapped order ready to ship",
                    ),
                ),
            endStates =
                listOf(
                    ContractEndState(
                        id = "end-shipped",
                        name = "Order shipped",
                        sourceIds = sources,
                    ),
                ),
            assumptions =
                listOf(
                    ContractAssumption(
                        id = "assume-payment",
                        text = "Payment is authorised upstream",
                        sourceIds = sources,
                    ),
                ),
        )
    }
}
