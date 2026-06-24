/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import dev.groknull.bpmner.authoring.BpmnDefaultFlowPort
import dev.groknull.bpmner.authoring.BpmnGeneratedEvent
import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.authoring.ValidatedOutline
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringConfig
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition
import dev.groknull.bpmner.authoring.internal.adapter.outbound.toSealed
import dev.groknull.bpmner.authoring.internal.domain.BpmnContractFidelityChecker
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelitySeverity
import dev.groknull.bpmner.authoring.internal.domain.BpmnGraphRenderer
import dev.groknull.bpmner.authoring.internal.domain.ProcessOutline
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.bpmn.RetryableBpmnGenerationException
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnLoggingConfig
import dev.groknull.bpmner.conformance.BpmnRepairScope
import dev.groknull.bpmner.contract.ProcessContractMarkdownRenderer
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.format
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.ruleset.BpmnNamingShapeAdvice
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@PrimaryAdapter
@Component
internal class LlmBpmnProcessGenerator(
    private val config: BpmnAuthoringConfig,
    private val logging: BpmnLoggingConfig,
    private val metricsCalculator: BpmnGeneratorMetrics,
    private val fidelityChecker: BpmnContractFidelityChecker,
    private val defaultFlowAssigner: BpmnDefaultFlowPort,
    private val contractRenderer: ProcessContractMarkdownRenderer,
    private val graphRenderer: BpmnGraphRenderer,
    private val eventPublisher: ApplicationEventPublisher,
) : BpmnProcessGenerator {
    private val logger = LoggerFactory.getLogger(LlmBpmnProcessGenerator::class.java)

    companion object {
        private const val MAIN_PHASE_OWNER = "generateBpmn"
    }

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

        val fidelityReport = fidelityChecker.checkDetailed(validatedContract.contract, outline.definition)
        if (fidelityReport.issues.any { it.severity == BpmnFidelitySeverity.ERROR }) {
            val violations =
                fidelityReport.issues
                    .filter { it.severity == BpmnFidelitySeverity.ERROR }
                    .joinToString(separator = System.lineSeparator()) { "- [${it.code}] ${it.message}" }
            throw RetryableBpmnGenerationException(
                "Generated BPMN does not faithfully encode the source contract topology " +
                    "(${fidelityReport.issues.size} fidelity issue(s)):${System.lineSeparator()}$violations",
            )
        }

        return ValidatedOutline(outline = outline, diagnostics = diagnostics, fidelityReport = fidelityReport)
    }

    override fun composeGraph(outline: ValidatedOutline): LaidOutProcessGraph {
        val definition = outline.definition

        val objectOwners = buildMap {
            put("process", MAIN_PHASE_OWNER)
            definition.nodes.forEach { put("nodes[id=${it.id}]", MAIN_PHASE_OWNER) }
            definition.sequences.forEach { put("sequences[id=${it.id}]", MAIN_PHASE_OWNER) }
        }
        val composed = dev.groknull.bpmner.bpmn.ComposedProcessGraph(
            definition = definition,
            objectOwnersByObjectRef = objectOwners,
        )
        logger.info(
            "Composition summary: nodes={}, edges={}, subprocesses={}",
            definition.nodes.size,
            definition.sequences.size,
            outline.outline.metrics.subprocessCount,
        )

        val elementOwners = buildMap {
            put(definition.processId, objectOwners.getValue("process"))
            definition.nodes.forEach { node ->
                val owner = objectOwners.getValue("nodes[id=${node.id}]")
                put(node.id, owner)
                put("${node.id}_di", owner)
            }
            definition.sequences.forEach { edge ->
                val owner = objectOwners.getValue("sequences[id=${edge.id}]")
                put(edge.id, owner)
                put("${edge.id}_di", owner)
            }
        }
        val owned = dev.groknull.bpmner.bpmn.OwnedElementGraph(
            composedGraph = composed,
            elementOwnersByElementId = elementOwners,
            objectOwnersByObjectRef = objectOwners,
        )

        val graph = LaidOutProcessGraph(ownedGraph = owned, definition = definition)
        logArtifactDump("graph", graph)
        return graph
    }

    override fun render(ready: ReadyBpmnContext, graph: LaidOutProcessGraph): RenderedBpmn {
        val rendered = graphRenderer.render(graph)
        eventPublisher.publishEvent(dev.groknull.bpmner.authoring.BpmnGeneratedEvent(ready.request, rendered))
        return rendered
    }

    override fun render(graph: LaidOutProcessGraph): RenderedBpmn {
        return graphRenderer.render(graph)
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
        if (!logging.dumpArtifacts) return
        val payload = artifact.toString().take(logging.artifactPreviewLength)
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
