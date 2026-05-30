/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

@file:Suppress("TooManyFunctions") // canonical inputs + model builders + render/schema accessors

package dev.groknull.bpmner.prompt

import com.embabel.common.ai.converters.FilteringJacksonOutputConverter
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnNamingShapeAdvice
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import java.util.function.Predicate

/**
 * Canonical inputs + measurement helpers shared between [PromptSizeProbeTest] and the
 * `update_prompt_baselines` binary. Realistic enough to exercise every conditional section
 * in each template (assessment evidence, missing areas, contract activities and decisions,
 * BPMN summary), so the measured size reflects production-shaped prompts.
 */
internal object PromptFixtures {
    val renderer = JinjavaTemplateRenderer()
    val objectMapper = jacksonObjectMapper()

    private val config = BpmnConfig()
    private val markdownRenderer = ProcessContractMarkdownRenderer()

    val canonicalRequest =
        BpmnRequest(
            processDescription =
            "When a customer submits a credit application, the system runs a credit check. " +
                "If the score is at least 700 the application is approved; otherwise it is " +
                "routed to a human underwriter for review.",
            styleGuide = "Use sentence case for task names.",
        )

    val canonicalAssessment =
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

    val canonicalContract =
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

    val canonicalBpmnSummary =
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

    fun contractExtractionModel(): Map<String, Any> = mapOf(
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

    fun bpmnGenerationModel(): Map<String, Any> = mapOf(
        "contractMarkdown" to markdownRenderer.render(canonicalContract).trim(),
        "processDescription" to canonicalRequest.processDescription,
        "styleGuide" to (canonicalRequest.styleGuide ?: ""),
        "namingShapeAdvice" to BpmnNamingShapeAdvice.allAdvice().map { advice ->
            val examples = advice.examples.joinToString(", ") { "\"$it\"" }
            val avoid = advice.antiExamples.joinToString(", ") { "\"$it\"" }
            "- ${advice.kind}: ${advice.shape}\n    examples: $examples\n    avoid:    $avoid"
        },
    )

    fun alignmentModel(): Map<String, Any> = mapOf(
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

    fun readinessModel(): Map<String, Any> = mapOf(
        "readyThreshold" to config.readiness.readyThreshold,
        "maxClarificationQuestions" to config.readiness.maxClarificationQuestions,
        "processDescription" to canonicalRequest.processDescription,
        "clarificationHistory" to canonicalRequest.clarificationHistory.map {
            mapOf("questionId" to it.questionId, "questionText" to it.questionText, "answerText" to it.answerText)
        },
    )

    fun renderContractPrompt(): String = renderer.renderLoadedTemplate("bpmner/extract_contract", contractExtractionModel())

    fun renderGenerationPrompt(): String = renderer.renderLoadedTemplate("bpmner/generate_bpmn", bpmnGenerationModel())

    fun renderAlignmentPrompt(): String = renderer.renderLoadedTemplate("bpmner/check_alignment", alignmentModel())

    fun renderReadinessPrompt(): String = renderer.renderLoadedTemplate("bpmner/assess_readiness", readinessModel())

    /**
     * Schema-format text the framework appends to the prompt before shipping to the LLM —
     * see `JacksonOutputConverter.getFormat()`. Using the same `FilteringJacksonOutputConverter`
     * + `FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT` customisation as production
     * (`ChatClientLlmOperations.kt:199`), with the default `Predicate { true }` filter
     * (production passes `interaction.fieldFilter` whose default is also accept-all —
     * see `LlmInteraction.kt:128`).
     */
    fun contractSchemaFormat(): String = schemaFormat(FlatProcessContract::class.java)

    fun generationSchemaFormat(): String = schemaFormat(FlatBpmnDefinition::class.java)

    fun alignmentSchemaFormat(): String = schemaFormat(AlignmentFindings::class.java)

    fun readinessSchemaFormat(): String = schemaFormat(ProcessInputAssessment::class.java)

    private fun <T : Any> schemaFormat(clazz: Class<T>): String = FilteringJacksonOutputConverter(
        clazz,
        objectMapper,
        Predicate { true },
    ).format
}
