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

package dev.groknull.bpmner.core

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.alignment.AlignedElement
import dev.groknull.bpmner.alignment.AlignmentVerdict
import dev.groknull.bpmner.alignment.BpmnAlignmentReport
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractActor
import dev.groknull.bpmner.contract.ContractArtifact
import dev.groknull.bpmner.contract.ContractAssumption
import dev.groknull.bpmner.contract.ContractBranch
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.TraceLink
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.ClarificationQuestion
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessDimensionScore
import dev.groknull.bpmner.readiness.ReadinessVerdict
import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BpmnGuardrailTypesTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `round trips process input assessment`() {
        val assessment = validAssessment()

        assertEquals(assessment, roundTrip<ProcessInputAssessment>(assessment))
    }

    @Test
    fun `round trips process contract`() {
        val contract = validContract()

        assertEquals(contract, roundTrip<ProcessContract>(contract))
    }

    @Test
    fun `round trips alignment report`() {
        val report = validAlignmentReport()

        assertEquals(report, roundTrip<BpmnAlignmentReport>(report))
    }

    @Test
    fun `round trips generated and blocked bpmn results`() {
        val generated =
            BpmnResult(
                outputFile = "order.bpmn",
                status = BpmnGenerationStatus.GENERATED,
                xml = "<definitions />",
                alignmentReport = validAlignmentReport(),
            )
        val blocked =
            BpmnResult(
                outputFile = "order.bpmn",
                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                readinessReport = validAssessment(),
                reportFile = "order.guardrails.json",
            )

        assertEquals(generated, roundTrip<BpmnResult>(generated))
        assertEquals(blocked, roundTrip<BpmnResult>(blocked))
    }

    @Test
    fun `valid guardrail objects pass bean validation`() {
        val result =
            BpmnResult(
                outputFile = "order.bpmn",
                status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                readinessReport = validAssessment(),
            )

        assertTrue(validator.validate(result).isEmpty())
        assertTrue(validator.validate(validContract()).isEmpty())
    }

    @Test
    fun `required guardrail fields fail bean validation`() {
        val invalidAssessment =
            ProcessInputAssessment(
                verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
                overallScore = -1,
                dimensions = emptyList(),
                clarificationQuestions =
                    listOf(
                        ClarificationQuestion(
                            id = "",
                            questionText = "",
                            options = List(9) { "option-$it" },
                        ),
                    ),
                rationale = "",
            )
        val invalidContract =
            ProcessContract(
                id = "",
                processName = "",
                summary = "",
                trigger = "",
                activities = emptyList(),
                endStates = emptyList(),
            )

        assertFalse(validator.validate(invalidAssessment).isEmpty())
        assertFalse(validator.validate(invalidContract).isEmpty())
    }

    private inline fun <reified T> roundTrip(value: T): T = objectMapper.readValue(objectMapper.writeValueAsString(value))

    private fun validAssessment(): ProcessInputAssessment =
        ProcessInputAssessment(
            verdict = ReadinessVerdict.NEEDS_CLARIFICATION,
            overallScore = 70,
            dimensions =
                listOf(
                    ReadinessDimensionScore(
                        dimension = ReadinessDimension.ACTORS_ROLES,
                        score = 50,
                        rationale = "Approver is unclear.",
                        missingAreas = listOf(MissingProcessArea.ACTOR_RESPONSIBILITY),
                    ),
                ),
            missingAreas = listOf(MissingProcessArea.ACTOR_RESPONSIBILITY),
            clarificationQuestions =
                listOf(
                    ClarificationQuestion(
                        id = "q1",
                        questionText = "Who approves the order?",
                        relatedMissingAreas = listOf(MissingProcessArea.ACTOR_RESPONSIBILITY),
                        relatedDimensions = listOf(ReadinessDimension.ACTORS_ROLES),
                    ),
                ),
            evidence =
                listOf(
                    SourceEvidence(
                        id = "ev1",
                        text = "Ship approved order",
                        sourceType = EvidenceSourceType.ORIGINAL_INPUT,
                    ),
                ),
            rationale = "One actor responsibility is underspecified.",
        )

    private fun validContract(): ProcessContract =
        ProcessContract(
            id = "contract-1",
            processName = "Ship order",
            summary = "Approved orders are packed and shipped.",
            trigger = "An approved order is received",
            triggerTraceLinks =
                listOf(
                    TraceLink(
                        id = "trace-trigger",
                        sourceId = "ev1",
                        targetId = "trigger",
                    ),
                ),
            activities = validContractActivities(),
            decisions = validContractDecisions(),
            actors = validContractActors(),
            artifacts = validContractArtifacts(),
            endStates = validContractEndStates(),
            assumptions = validContractAssumptions(),
            traceLinks = validContractTraceLinks(),
        )

    private fun validContractActivities() =
        listOf(
            ContractActivity(
                id = "activity-pack",
                name = "Pack order",
                actorId = "actor-warehouse",
                outputArtifactIds = listOf("artifact-package"),
            ),
        )

    private fun validContractDecisions() =
        listOf(
            ContractDecision(
                id = "decision-stock",
                question = "Is stock available?",
                branches =
                    listOf(
                        ContractBranch(
                            id = "branch-yes",
                            label = "Yes",
                            condition = "Stock is available",
                        ),
                    ),
            ),
        )

    private fun validContractActors() =
        listOf(
            ContractActor(
                id = "actor-warehouse",
                name = "Warehouse team",
                role = "Packs and ships orders",
            ),
        )

    private fun validContractArtifacts() =
        listOf(
            ContractArtifact(
                id = "artifact-package",
                name = "Packed order",
            ),
        )

    private fun validContractEndStates() =
        listOf(
            ContractEndState(
                id = "end-shipped",
                name = "Order shipped",
            ),
        )

    private fun validContractAssumptions() =
        listOf(
            ContractAssumption(
                id = "assumption-1",
                text = "Approved means payment has cleared.",
            ),
        )

    private fun validContractTraceLinks() =
        listOf(
            TraceLink(
                id = "trace-1",
                sourceId = "ev1",
                targetId = "activity-pack",
            ),
        )

    private fun validAlignmentReport(): BpmnAlignmentReport =
        BpmnAlignmentReport(
            verdict = AlignmentVerdict.ALIGNED,
            bpmnSummary =
                BpmnDefinitionSummary(
                    processId = "Process_Order",
                    processName = "Ship order",
                    elements =
                        listOf(
                            BpmnSummaryElement(
                                id = "Task_Pack",
                                type = "USER_TASK",
                                name = "Pack order",
                            ),
                        ),
                    flows =
                        listOf(
                            BpmnSummaryFlow(
                                id = "Flow_1",
                                sourceRef = "StartEvent_1",
                                targetRef = "Task_Pack",
                            ),
                        ),
                ),
            alignedElements =
                listOf(
                    AlignedElement(
                        id = "aligned-1",
                        contractElementId = "activity-pack",
                        bpmnElementId = "Task_Pack",
                        classification = AlignmentClassification.SUPPORTED,
                        rationale = "The BPMN task implements the contract activity.",
                    ),
                ),
            traceLinks =
                listOf(
                    TraceLink(
                        id = "trace-align-1",
                        sourceId = "activity-pack",
                        targetId = "Task_Pack",
                    ),
                ),
            rationale = "All required contract activities are represented.",
        )
}
