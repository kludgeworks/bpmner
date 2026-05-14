package dev.groknull.bpmner.contract.internal.domain

import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.ContractActivity
import dev.groknull.bpmner.core.ContractActor
import dev.groknull.bpmner.core.ContractArtifact
import dev.groknull.bpmner.core.ContractAssumption
import dev.groknull.bpmner.core.ContractBranch
import dev.groknull.bpmner.core.ContractDecision
import dev.groknull.bpmner.core.ContractEndState
import dev.groknull.bpmner.core.ProcessContract
import dev.groknull.bpmner.core.TraceLink
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
            - assume-payment: Payment is authorised upstream (trace: ev1)

            ## Trace links
            - ev1 → contract-1 [supported]
            """.trimIndent()

        assertEquals(expected, markdown)
    }

    @Test
    fun `omits empty sections in a minimal contract`() {
        val minimal =
            ProcessContract(
                id = "min",
                processName = "Approve",
                summary = "Approves things.",
                trigger = "Request arrives",
                activities =
                    listOf(
                        ContractActivity(id = "a", name = "Review", traceLinks = listOf(trace())),
                        ContractActivity(id = "b", name = "Decide", traceLinks = listOf(trace())),
                    ),
                endStates = listOf(ContractEndState(id = "e", name = "Done", traceLinks = listOf(trace()))),
            )

        val markdown = renderer.render(minimal)

        assertTrue(markdown.contains("# Approve"))
        assertTrue(markdown.contains("## Activities"))
        assertTrue(markdown.contains("## End states"))
        assertTrue(!markdown.contains("## Actors"))
        assertTrue(!markdown.contains("## Decisions"))
        assertTrue(!markdown.contains("## Artifacts"))
        assertTrue(!markdown.contains("## Assumptions"))
        assertTrue(!markdown.contains("## Trace links"))
    }

    private fun trace(target: String = "self") =
        TraceLink(
            id = "trace-$target",
            sourceId = "ev1",
            targetId = target,
            classification = AlignmentClassification.SUPPORTED,
        )

    private fun fullContract() =
        ProcessContract(
            id = "contract-1",
            processName = "Ship order",
            summary = "Approved orders are packed and shipped.",
            trigger = "An order is submitted",
            triggerTraceLinks = listOf(trace("trigger")),
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
                        traceLinks = listOf(trace("a-pack")),
                    ),
                    ContractActivity(
                        id = "a-ship",
                        name = "Ship order",
                        actorId = "actor-warehouse",
                        traceLinks = listOf(trace("a-ship")),
                    ),
                ),
            decisions =
                listOf(
                    ContractDecision(
                        id = "d-stock",
                        question = "Is the item in stock?",
                        branches =
                            listOf(
                                ContractBranch(id = "b-yes", label = "In stock", condition = "stock > 0"),
                                ContractBranch(id = "b-no", label = "Out of stock", condition = "stock == 0"),
                            ),
                        traceLinks = listOf(trace("d-stock")),
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
                        traceLinks = listOf(trace("end-shipped")),
                    ),
                ),
            assumptions =
                listOf(
                    ContractAssumption(
                        id = "assume-payment",
                        text = "Payment is authorised upstream",
                        traceLinks =
                            listOf(
                                TraceLink(
                                    id = "trace-assume-payment",
                                    sourceId = "ev1",
                                    targetId = "assume-payment",
                                ),
                            ),
                    ),
                ),
            traceLinks =
                listOf(
                    TraceLink(
                        id = "trace-overall",
                        sourceId = "ev1",
                        targetId = "contract-1",
                        classification = AlignmentClassification.SUPPORTED,
                    ),
                ),
        )
}
