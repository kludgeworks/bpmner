/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.format
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnNamingShapeAdvice
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.generation.BpmnContractFidelityChecker
import dev.groknull.bpmner.generation.BpmnFidelitySeverity
import dev.groknull.bpmner.generation.BpmnProcessGenerator
import dev.groknull.bpmner.generation.DefaultFlowAssigner
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.ProcessOutline
import dev.groknull.bpmner.generation.ValidatedOutline
import dev.groknull.bpmner.generation.toSealed
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnRepairScope
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
internal class LlmBpmnProcessGenerator(
    private val config: BpmnConfig,
    private val metricsCalculator: BpmnGeneratorMetrics,
    private val fidelityChecker: BpmnContractFidelityChecker,
    private val defaultFlowAssigner: DefaultFlowAssigner,
    private val contractRenderer: ProcessContractMarkdownRenderer,
) : BpmnProcessGenerator {
    private val logger = LoggerFactory.getLogger(LlmBpmnProcessGenerator::class.java)

    /**
     * Single LLM-driven action: contract → outline → fidelity-checked [ValidatedOutline].
     *
     * Phase 5 (#220) collapsed the previous two-action shape (`createProcessOutline` + `validateOutline`)
     * because the LLM call and its deterministic post-validation are one logical step. The
     * `?: error("Outline generator failed…")` defense that lived on the prior `createObject` call is
     * gone with the inline — `createObject` returns non-null by Embabel's contract and throws
     * `InvalidLlmReturnFormatException` on failure (see [Embabel `LlmOperations.createObject`]).
     */
    override fun createOutline(
        ready: ReadyBpmnContext,
        validatedContract: ValidatedProcessContract,
        context: OperationContext,
    ): ValidatedOutline {
        val request = ready.request
        if (!validatedContract.isValid) {
            val issues =
                validatedContract.report.issues
                    .joinToString(separator = System.lineSeparator()) { "- ${it.format()}" }
            error("Cannot generate BPMN from an invalid process contract:${System.lineSeparator()}$issues")
        }
        val promptRunner = config.generator.promptRunner(context)
        // Typed few-shot examples for the non-obvious topologies (fork/join, data, subprocesses, pools).
        val creating = GenerationExamples.all
            .fold(promptRunner.creating(FlatBpmnDefinition::class.java)) { acc, (label, example) ->
                acc.withExample(label, example)
            }
        val flat = creating.fromTemplate("bpmner/generate_bpmn", templateModel(request, validatedContract))
        val rawDefinition = try {
            flat.toSealed()
        } catch (e: IllegalArgumentException) {
            // FlatBpmnDefinition.toSealed() throws when the LLM emits a structurally
            // incomplete node. Re-throw as the framework's typed format exception so the
            // planner's outline-retry path engages instead of the process hard-aborting.
            throw InvalidLlmReturnFormatException(
                llmReturn = flat.toString(),
                expectedType = FlatBpmnDefinition::class.java,
                cause = e,
            )
        }
        // Stamp isDefault on outbound flows from EXCLUSIVE_GATEWAY nodes that the contract
        // marks as DefaultBranch. The LLM is unreliable on this attribute, so we
        // deterministically apply it here BEFORE the fidelity check runs (which fires
        // DEFAULT_FLOW_MISSING as ERROR and would abort the pipeline). The repair engine
        // also re-runs DefaultFlowAssigner on every repair candidate as a second line of
        // defence against LLM drift during refinement iterations.
        val definitionWithDefaults = defaultFlowAssigner.assign(validatedContract.contract, rawDefinition)
        val outline =
            ProcessOutline(
                request = request,
                definition = definitionWithDefaults,
                metrics = metricsCalculator.calculate(definitionWithDefaults),
            )
        logger.info(
            "Outline summary: phases={}, xorBranches={}, orBranches={}, parallelBranches={}, loops={}, subprocesses={}",
            outline.metrics.phaseCount,
            outline.metrics.exclusiveBranchCount,
            outline.metrics.inclusiveBranchCount,
            outline.metrics.parallelBranchCount,
            outline.metrics.loopCount,
            outline.metrics.subprocessCount,
        )
        logArtifactDump("process-outline", outline)

        val diagnostics = outlineDiagnostics(outline)
        if (diagnostics.isNotEmpty()) {
            logger.warn("Outline validation summary: {} issue(s)", diagnostics.size)
        }

        val fidelityReport = fidelityChecker.check(validatedContract.contract, outline.definition)
        if (fidelityReport.issues.any { it.severity == BpmnFidelitySeverity.ERROR }) {
            val violations =
                fidelityReport.issues
                    .filter { it.severity == BpmnFidelitySeverity.ERROR }
                    .joinToString(separator = System.lineSeparator()) { "- [${it.code}] ${it.message}" }
            error(
                "Generated BPMN does not faithfully encode the source contract topology " +
                    "(${fidelityReport.issues.size} fidelity issue(s)):${System.lineSeparator()}$violations",
            )
        }

        return ValidatedOutline(outline = outline, diagnostics = diagnostics, fidelityReport = fidelityReport)
    }

    private fun outlineDiagnostics(outline: ProcessOutline): List<BpmnDiagnostic> = buildList {
        if (outline.definition.processId.isBlank()) {
            add(
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = "outline must define a non-blank processId",
                    objectRef = "process",
                    repairScope = BpmnRepairScope.OUTLINE,
                ),
            )
        }
        if (outline.definition.processName.isBlank()) {
            add(
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = "outline must define a non-blank processName",
                    objectRef = "process",
                    repairScope = BpmnRepairScope.OUTLINE,
                ),
            )
        }
    }

    private fun logArtifactDump(
        label: String,
        artifact: Any,
    ) {
        if (!config.logging.dumpArtifacts) return
        val payload = artifact.toString().take(config.logging.artifactPreviewLength)
        logger.debug("Artifact dump [{}]: {}", label, payload)
    }

    private fun templateModel(
        request: BpmnRequest,
        validatedContract: ValidatedProcessContract,
    ): Map<String, Any> = mapOf(
        "contractMarkdown" to contractRenderer.render(validatedContract.contract).trim(),
        "processDescription" to request.processDescription,
        "styleGuide" to (request.styleGuide ?: ""),
        "namingShapeAdvice" to BpmnNamingShapeAdvice.allAdvice().map { advice ->
            val examples = advice.examples.joinToString(", ") { "\"$it\"" }
            val avoid = advice.antiExamples.joinToString(", ") { "\"$it\"" }
            "- ${advice.kind}: ${advice.shape}\n    examples: $examples\n    avoid:    $avoid"
        },
    )
}
