/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions")

package dev.groknull.bpmner.prompt

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnNamingShapeAdvice
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.MissingProcessArea
import dev.groknull.bpmner.core.ReadinessDimension
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Ratchet test for the size of LLM-facing prompts.
 *
 * Reads baselines from `src/test/resources/prompt-baselines.json`. Fails if any template's rendered
 * prompt grows past its ceiling; logs a warning if it shrinks more than 15% below the recorded
 * baseline, so the developer can lock in the gain by updating the baseline file.
 *
 * The baselines file is the canonical, reviewable record of prompt size targets — much more
 * visible in PR diffs than constants buried in test code.
 */
class PromptSizeProbeTest {
    private val baselines: JsonNode = loadBaselines()
    private val config = BpmnConfig()
    private val renderer = JinjavaTemplateRenderer()
    private val markdownRenderer = ProcessContractMarkdownRenderer()

    @Test
    fun `contract extraction prompt stays within budget`() {
        val prompt = renderer.renderLoadedTemplate("bpmner/extract_contract", contractExtractionModel())
        checkBaseline("contractPrompt", prompt.length)
    }

    @Test
    fun `bpmn generation prompt stays within budget`() {
        val prompt = renderer.renderLoadedTemplate("bpmner/generate_bpmn", bpmnGenerationModel())
        checkBaseline("generationPrompt", prompt.length)
    }

    @Test
    fun `alignment prompt stays within budget`() {
        val prompt = renderer.renderLoadedTemplate("bpmner/check_alignment", alignmentModel())
        checkBaseline("alignmentPrompt", prompt.length)
    }

    @Test
    fun `readiness prompt stays within budget`() {
        val prompt = renderer.renderLoadedTemplate("bpmner/assess_readiness", readinessModel())
        checkBaseline("readinessPrompt", prompt.length)
    }

    private fun checkBaseline(key: String, actual: Int) {
        val node =
            baselines.path("baselines").path(key).takeIf { !it.isMissingNode }
                ?: error("Missing baseline '$key' in prompt-baselines.json")
        val baseline = node.path("chars").asInt(0)
        val ceiling = node.path("ceiling").asInt(Int.MAX_VALUE)

        logger.info("PromptSizeProbe[$key]: actual={} chars (baseline={}, ceiling={})", actual, baseline, ceiling)

        assertTrue(
            actual <= ceiling,
            "$key prompt exceeded ceiling of $ceiling chars (was $actual). " +
                "If intentional, raise the ceiling in src/test/resources/prompt-baselines.json " +
                "and explain the change in the 'reason' field.",
        )

        if (baseline > 0 && actual < baseline * 0.85) {
            logger.warn(
                "PromptSizeProbe[{}]: prompt shrank from {} to {} chars (>15% reduction). " +
                    "Update src/test/resources/prompt-baselines.json to lock in the improvement.",
                key,
                baseline,
                actual,
            )
        }
    }

    private fun contractExtractionModel(): Map<String, Any> = mapOf(
        "maxAssumptions" to config.contract.maxAssumptions,
        "rationale" to canonicalAssessment.rationale,
        "missingAreas" to canonicalAssessment.missingAreas.map { it.name },
        "evidence" to canonicalAssessment.evidence.map { mapOf("id" to it.id, "text" to it.text) },
        "clarificationHistory" to canonicalRequest.clarificationHistory.map {
            mapOf("questionId" to it.questionId, "questionText" to it.questionText, "answerText" to it.answerText)
        },
        "styleGuide" to (canonicalRequest.styleGuide ?: ""),
        "processDescription" to canonicalRequest.processDescription,
    )

    private fun bpmnGenerationModel(): Map<String, Any> = mapOf(
        "contractMarkdown" to markdownRenderer.render(canonicalContract).trim(),
        "processDescription" to canonicalRequest.processDescription,
        "styleGuide" to (canonicalRequest.styleGuide ?: ""),
        "namingShapeAdvice" to BpmnNamingShapeAdvice.allAdvice().map { advice ->
            val examples = advice.examples.joinToString(", ") { "\"$it\"" }
            val avoid = advice.antiExamples.joinToString(", ") { "\"$it\"" }
            "- ${advice.kind}: ${advice.shape}\n    examples: $examples\n    avoid:    $avoid"
        },
    )

    private fun alignmentModel(): Map<String, Any> = mapOf(
        "contractMarkdown" to markdownRenderer.render(canonicalContract).trim(),
        "processId" to canonicalBpmnSummary.processId,
        "processName" to canonicalBpmnSummary.processName,
        "elementLines" to canonicalBpmnSummary.elements.map { element ->
            "[${element.id}] ${element.type}: ${element.name ?: "(unnamed)"}"
        },
        "flowLines" to canonicalBpmnSummary.flows.map { flow ->
            val condition = flow.conditionExpression?.let { " [if $it]" } ?: ""
            val name = flow.name?.let { " ($it)" } ?: ""
            "[${flow.id}] ${flow.sourceRef} → ${flow.targetRef}$condition$name"
        },
        "unreachableElementIds" to canonicalBpmnSummary.unreachableElementIds,
        "processDescription" to canonicalRequest.processDescription,
    )

    private fun readinessModel(): Map<String, Any> = mapOf(
        "readyThreshold" to config.readiness.readyThreshold,
        "maxClarificationQuestions" to config.readiness.maxClarificationQuestions,
        "dimensions" to ReadinessDimension.entries.map { it.name },
        "missingAreas" to MissingProcessArea.entries.map { it.name },
        "processDescription" to canonicalRequest.processDescription,
        "clarificationHistory" to canonicalRequest.clarificationHistory.map {
            mapOf("questionId" to it.questionId, "questionText" to it.questionText, "answerText" to it.answerText)
        },
    )

    private fun loadBaselines(): JsonNode {
        val stream =
            javaClass.classLoader.getResourceAsStream("prompt-baselines.json")
                ?: error("prompt-baselines.json not found on the test classpath")
        return stream.use { jacksonObjectMapper().readTree(it) }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(PromptSizeProbeTest::class.java)

        // Canonical inputs reused across the four template probes. Realistic enough to exercise
        // every conditional section (assessment evidence, missing areas, contract activities and
        // decisions, BPMN summary), so the measured size reflects production-shaped prompts.

        private val canonicalRequest =
            BpmnRequest(
                processDescription =
                "When a customer submits a credit application, the system runs a credit check. " +
                    "If the score is at least 700 the application is approved; otherwise it is " +
                    "routed to a human underwriter for review.",
                styleGuide = "Use sentence case for task names.",
            )

        private val canonicalAssessment =
            ProcessInputAssessment(
                verdict = ReadinessVerdict.READY,
                overallScore = 82,
                dimensions = emptyList(),
                missingAreas = emptyList(),
                evidence =
                listOf(
                    SourceEvidence(
                        id = "ev1",
                        text = "Customer submits credit application",
                        sourceType = EvidenceSourceType.ORIGINAL_INPUT,
                    ),
                    SourceEvidence(
                        id = "ev2",
                        text = "Score >= 700 approves the application",
                        sourceType = EvidenceSourceType.ORIGINAL_INPUT,
                    ),
                ),
                rationale = "The source describes a complete credit-decisioning workflow.",
            )

        private val canonicalContract =
            ProcessContract(
                id = "contract-credit-application",
                processName = "Credit application",
                summary = "Credit applications are scored and approved automatically when the score is high enough.",
                trigger = "Customer submits credit application",
                triggerSourceIds = listOf("ev1"),
                activities =
                listOf(
                    ContractActivity(id = "act-score", name = "Run credit check", sourceIds = listOf("ev1")),
                    ContractActivity(id = "act-review", name = "Review credit application", sourceIds = listOf("ev2")),
                ),
                decisions =
                listOf(
                    ContractDecision(
                        id = "dec-score",
                        question = "Is the score at least 700?",
                        branches =
                        listOf(
                            ConditionalBranch(id = "br-approve", label = "Approve", condition = "score >= 700"),
                            ConditionalBranch(id = "br-review", label = "Review", condition = "score < 700"),
                        ),
                        sourceIds = listOf("ev2"),
                    ),
                ),
                endStates =
                listOf(
                    ContractEndState(id = "end-approved", name = "Application approved", sourceIds = listOf("ev2")),
                    ContractEndState(id = "end-reviewed", name = "Application reviewed", sourceIds = listOf("ev1")),
                ),
            )

        private val canonicalBpmnSummary =
            BpmnDefinitionSummary(
                processId = "Process_credit",
                processName = "Credit application",
                elements =
                listOf(
                    BpmnSummaryElement(id = "StartEvent_1", type = "START_EVENT", name = "Application submitted"),
                    BpmnSummaryElement(id = "act-score", type = "SERVICE_TASK", name = "Run credit check"),
                    BpmnSummaryElement(id = "Gateway_1", type = "EXCLUSIVE_GATEWAY", name = "Score >= 700?"),
                    BpmnSummaryElement(id = "act-review", type = "USER_TASK", name = "Review credit application"),
                    BpmnSummaryElement(id = "EndEvent_approved", type = "END_EVENT", name = "Approved"),
                    BpmnSummaryElement(id = "EndEvent_reviewed", type = "END_EVENT", name = "Reviewed"),
                ),
                flows =
                listOf(
                    BpmnSummaryFlow(id = "Flow_1", sourceRef = "StartEvent_1", targetRef = "act-score"),
                    BpmnSummaryFlow(id = "Flow_2", sourceRef = "act-score", targetRef = "Gateway_1"),
                    BpmnSummaryFlow(
                        id = "Flow_3",
                        sourceRef = "Gateway_1",
                        targetRef = "EndEvent_approved",
                        conditionExpression = "score >= 700",
                    ),
                    BpmnSummaryFlow(
                        id = "Flow_4",
                        sourceRef = "Gateway_1",
                        targetRef = "act-review",
                        conditionExpression = "score < 700",
                    ),
                    BpmnSummaryFlow(id = "Flow_5", sourceRef = "act-review", targetRef = "EndEvent_reviewed"),
                ),
            )
    }
}
