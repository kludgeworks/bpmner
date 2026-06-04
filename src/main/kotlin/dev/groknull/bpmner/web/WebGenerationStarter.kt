/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.web

import dev.groknull.bpmner.api.GenerationMode
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.BpmnAgentInvoker
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.readiness.ReadinessReportWriter
import dev.groknull.bpmner.readiness.ReadinessVerdict
import org.springframework.stereotype.Service

sealed interface WebGenerationStartOutcome {
    data class Started(
        val processId: String,
    ) : WebGenerationStartOutcome

    data class Blocked(
        val result: BpmnResult,
    ) : WebGenerationStartOutcome
}

@Service
class WebGenerationStarter(
    private val agentInvoker: BpmnAgentInvoker,
    private val readinessInvoker: BpmnReadinessInvoker,
    private val readinessReportWriter: ReadinessReportWriter,
) {
    fun start(request: WebGenerationRequest): WebGenerationStartOutcome {
        val bpmnRequest =
            BpmnRequest(
                processDescription = request.processDescription,
                styleGuide = request.styleGuide?.trim()?.takeIf { it.isNotEmpty() },
                outputFile = null,
                mode = GenerationMode.SINGLE_SHOT,
            )
        val assessment = readinessInvoker.assess(bpmnRequest)
        return when (assessment.verdict) {
            ReadinessVerdict.READY -> WebGenerationStartOutcome.Started(
                agentInvoker.startAsync(bpmnRequest, assessment),
            )

            ReadinessVerdict.NEEDS_CLARIFICATION -> {
                val reportPath =
                    readinessReportWriter.writeReport(
                        originalInput = bpmnRequest.processDescription,
                        assessment = assessment,
                        outputFile = bpmnRequest.outputFile,
                    )
                WebGenerationStartOutcome.Blocked(
                    BpmnResult(
                        outputFile = bpmnRequest.outputFile,
                        status = BpmnGenerationStatus.NEEDS_CLARIFICATION,
                        readinessReport = assessment,
                        reportFile = reportPath,
                    ),
                )
            }
        }
    }
}
