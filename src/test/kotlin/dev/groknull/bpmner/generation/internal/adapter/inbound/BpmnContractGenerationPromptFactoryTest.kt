/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.TraceLink
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.AlignmentClassification
import dev.groknull.bpmner.core.BpmnRequest
import kotlin.test.Test
import kotlin.test.assertTrue

class BpmnContractGenerationPromptFactoryTest {
    private val factory = BpmnContractGenerationPromptFactory()

    @Test
    fun `builds a contract-first BPMN generation prompt with traceability context`() {
        val prompt =
            factory.prompt(
                request =
                    BpmnRequest(
                        processDescription = "Customer submits a claim and the team reviews it.",
                        styleGuide = "Use sentence case task names.",
                    ),
                validatedContract = ValidatedProcessContract(contract(), ContractValidationReport(emptyList())),
            )

        assertTrue(prompt.contains("validated ProcessContract is the primary and authoritative generation input"))
        assertTrue(prompt.contains("Original input for traceability only:"))
        assertTrue(
            prompt.indexOf("Primary validated ProcessContract:") <
                prompt.indexOf("Original input for traceability only:"),
        )
        assertTrue(prompt.contains("Trigger: Claim is submitted"))
        assertTrue(prompt.contains("- a-review: Claims team reviews claim (actor: actor-claims)"))
        assertTrue(prompt.contains("- a-rework: Request corrected claim details"))
        assertTrue(prompt.contains("- d-complete: Is the claim complete?"))
        assertTrue(prompt.contains("- b-complete"))
        assertTrue(prompt.contains("- b-rework"))
        assertTrue(prompt.contains("- end-approved: Claim approved"))
        assertTrue(prompt.contains("- end-rejected: Claim rejected"))
        assertTrue(prompt.contains("- assume-cutoff: Claims after cutoff move to next business day"))
        assertTrue(prompt.contains("Use sentence case task names."))
        assertTrue(prompt.contains("Do not add unsupported tasks, decisions, branches, actors, or end states."))
        assertTrue(prompt.contains("layout coordinates, waypoints, sequence flows"))
        assertTrue(prompt.contains("routing-only converging gateways"))
        assertTrue(prompt.contains("Leave routing-only converging gateways unnamed."))
    }

    @Suppress("LongMethod") // exhaustive contract fixture — splitting it obscures the assertions
    private fun contract(): ProcessContract =
        ProcessContract(
            id = "contract-claim",
            processName = "Handle claim",
            summary = "Claims are reviewed, routed for rework when incomplete, and closed.",
            trigger = "Claim is submitted",
            triggerTraceLinks = listOf(trace("trigger")),
            actors = listOf(ContractActor(id = "actor-claims", name = "Claims team")),
            activities =
                listOf(
                    ContractActivity(
                        id = "a-review",
                        name = "Claims team reviews claim",
                        actorId = "actor-claims",
                        traceLinks = listOf(trace("a-review")),
                    ),
                    ContractActivity(
                        id = "a-rework",
                        name = "Request corrected claim details",
                        actorId = "actor-claims",
                        traceLinks = listOf(trace("a-rework")),
                    ),
                ),
            decisions =
                listOf(
                    ContractDecision(
                        id = "d-complete",
                        question = "Is the claim complete?",
                        branches =
                            listOf(
                                ContractBranch(id = "b-complete", label = "Complete", condition = "claim is complete"),
                                ContractBranch(
                                    id = "b-rework",
                                    label = "Needs rework",
                                    condition = "claim is incomplete",
                                ),
                            ),
                        traceLinks = listOf(trace("d-complete")),
                    ),
                ),
            endStates =
                listOf(
                    ContractEndState(
                        id = "end-approved",
                        name = "Claim approved",
                        traceLinks = listOf(trace("end-approved")),
                    ),
                    ContractEndState(
                        id = "end-rejected",
                        name = "Claim rejected",
                        traceLinks = listOf(trace("end-rejected")),
                    ),
                ),
            assumptions =
                listOf(
                    ContractAssumption(
                        id = "assume-cutoff",
                        text = "Claims after cutoff move to next business day",
                        traceLinks = listOf(trace("assume-cutoff")),
                    ),
                ),
        )

    private fun trace(target: String) =
        TraceLink(
            id = "trace-$target",
            sourceId = "ev1",
            targetId = target,
            classification = AlignmentClassification.SUPPORTED,
        )
}
