/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompt

import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.alignment.AlignmentFindings
import dev.groknull.bpmner.alignment.BpmnDefinitionSummary
import dev.groknull.bpmner.alignment.BpmnSummaryElement
import dev.groknull.bpmner.alignment.BpmnSummaryFlow
import dev.groknull.bpmner.alignment.internal.adapter.inbound.BpmnAlignmentPromptFactory
import dev.groknull.bpmner.contract.ConditionalBranch
import dev.groknull.bpmner.contract.ContractActivity
import dev.groknull.bpmner.contract.ContractDecision
import dev.groknull.bpmner.contract.ContractEndState
import dev.groknull.bpmner.contract.ContractValidationReport
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.internal.adapter.inbound.BpmnContractPromptFactory
import dev.groknull.bpmner.contract.internal.adapter.inbound.FlatProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.EvidenceSourceType
import dev.groknull.bpmner.core.SourceEvidence
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.internal.adapter.inbound.BpmnGenerationGatePromptFactory
import dev.groknull.bpmner.generation.internal.adapter.inbound.BpmnGeneratorPromptFactory
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import dev.groknull.bpmner.readiness.ReadinessVerdict
import dev.groknull.bpmner.readiness.internal.adapter.inbound.BpmnReadinessPromptFactory

/**
 * Canonical inputs + per-site [PromptSite] bundles shared between [PromptSizeProbeTest] and
 * the `update_prompt_baselines` binary. Realistic enough to exercise every conditional
 * section in each template (assessment evidence, missing areas, contract activities and
 * decisions, BPMN summary), so the measured size reflects production-shaped prompts.
 */
internal object PromptFixtures {
    private val renderer = JinjavaTemplateRenderer()
    private val objectMapper = jacksonObjectMapper()
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

    // contract / alignment / readiness agents attach the BpmnRequest as a PromptContributor
    // (`.withPromptContributor(request)`), so its `contribution()` ships in their system message
    // and must be measured in fullPayload(). The generation agent does NOT
    // (BpmnGeneratorAgent.kt:82 is a bare `promptRunner(context)`), so its site carries no
    // request contribution. Personas (also contributors) are stable config and intentionally
    // excluded — this probe tracks the drift-prone surface: template + request contribution + schema.
    private val requestContribution: () -> String = { canonicalRequest.contribution() }

    val contractFactory = BpmnContractPromptFactory(config)
    val generatorFactory = BpmnGeneratorPromptFactory(markdownRenderer)
    val alignmentFactory = BpmnAlignmentPromptFactory(markdownRenderer)
    val readinessFactory = BpmnReadinessPromptFactory(config)
    val gateFactory = BpmnGenerationGatePromptFactory()

    val contract: PromptSite<FlatProcessContract> = site(
        template = "bpmner/extract_contract",
        outputType = FlatProcessContract::class.java,
        contribution = requestContribution,
    ) { contractFactory.templateModel(canonicalRequest, canonicalAssessment) }

    val generation: PromptSite<FlatBpmnDefinition> = site(
        template = "bpmner/generate_bpmn",
        outputType = FlatBpmnDefinition::class.java,
    ) {
        generatorFactory.templateModel(
            canonicalRequest,
            ValidatedProcessContract(canonicalContract, ContractValidationReport(emptyList())),
        )
    }

    val alignment: PromptSite<AlignmentFindings> = site(
        template = "bpmner/check_alignment",
        outputType = AlignmentFindings::class.java,
        contribution = requestContribution,
    ) { alignmentFactory.templateModel(canonicalRequest, canonicalContract, canonicalBpmnSummary) }

    val readiness: PromptSite<ProcessInputAssessment> = site(
        template = "bpmner/assess_readiness",
        outputType = ProcessInputAssessment::class.java,
        contribution = requestContribution,
    ) { readinessFactory.templateModel(canonicalRequest) }

    private fun <T : Any> site(
        template: String,
        outputType: Class<T>,
        contribution: () -> String = { "" },
        model: () -> Map<String, Any>,
    ): PromptSite<T> = PromptSite(template, outputType, renderer, objectMapper, model, contribution)
}
