/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.inbound

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import com.embabel.agent.core.support.InvalidLlmReturnTypeException
import dev.groknull.bpmner.authoring.BpmnAgentInvoker
import dev.groknull.bpmner.authoring.BpmnContractConformancePort
import dev.groknull.bpmner.authoring.BpmnContractFidelityPort
import dev.groknull.bpmner.authoring.BpmnGeneratedEvent
import dev.groknull.bpmner.authoring.BpmnGraphComposedEvent
import dev.groknull.bpmner.authoring.BpmnOutlineGenerationException
import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.authoring.BpmnRenderer
import dev.groknull.bpmner.authoring.ContractCorrection
import dev.groknull.bpmner.authoring.ValidatedOutline
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringConfig
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition
import dev.groknull.bpmner.authoring.internal.adapter.outbound.toSealed
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelityReport
import dev.groknull.bpmner.authoring.internal.domain.BpmnFidelitySeverity
import dev.groknull.bpmner.authoring.internal.domain.OutlineConservation
import dev.groknull.bpmner.authoring.internal.domain.ProcessOutline
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnLoggingConfig
import dev.groknull.bpmner.conformance.BpmnRepairScope
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.llm.PromptJsonRenderer
import dev.groknull.bpmner.llm.publishOnInvalidLlmReturn
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.ruleset.BpmnNamingShapeAdvice
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

@InfrastructureRing
@Component
internal class LlmBpmnProcessGenerator(
    private val config: BpmnAuthoringConfig,
    private val logging: BpmnLoggingConfig,
    private val metricsCalculator: BpmnGeneratorMetrics,
    private val fidelityChecker: BpmnContractFidelityPort,
    private val conformancePort: BpmnContractConformancePort,
    private val jsonRenderer: PromptJsonRenderer,
    private val renderer: BpmnRenderer,
    private val agentInvoker: BpmnAgentInvoker,
    private val eventPublisher: ApplicationEventPublisher,
) : BpmnProcessGenerator {
    private val logger = LoggerFactory.getLogger(LlmBpmnProcessGenerator::class.java)

    companion object {
        private const val MAIN_PHASE_OWNER = "generateBpmn"
    }

    /**
     * Single LLM-driven action: contract → outline → fidelity-checked [ValidatedOutline].
     *
     * The fidelity check is corrective, not stochastic: on a fidelity violation, the next
     * attempt's prompt states what the previous attempt got wrong
     * ([templateModel]'s `previousFailure`), up to [BpmnAuthoringConfig.maxOutlineAttempts].
     *
     * The loop lives here rather than delegating to the framework's action retry because that
     * retry re-invokes the action from scratch and hands it no per-attempt state: the diagnostic
     * computed on one attempt cannot reach the next, so the model would be asked the identical
     * question every time and never told it got it wrong.
     *
     * The LLM call and its deterministic post-validation are one logical step — no intermediate
     * `createObject` null-guard is needed because `createObject` returns non-null by Embabel's
     * contract and throws `InvalidLlmReturnFormatException` on failure
     * (see [Embabel `LlmOperations.createObject`]).
     */
    override fun createOutline(
        ready: ReadyBpmnContext,
        validatedContract: ValidatedProcessContract,
        context: OperationContext,
    ): ValidatedOutline {
        val request = ready.request
        val promptRunner = config.generator.promptRunner(context)

        var previousFailure: String? = null
        // The previous attempt, so a conserving retry can be told what it
        // dropped from a surviving successful outline without being asked to.
        var previousAttempt: OutlineAttempt? = null
        for (attempt in 1..config.maxOutlineAttempts) {
            val currentAttempt = attemptOutline(request, validatedContract, promptRunner, previousFailure)
            if (currentAttempt.fidelityReport.isValid) {
                val drops = detectConservationDrops(validatedContract.contract, previousAttempt, currentAttempt)
                if (drops.isEmpty()) {
                    return ValidatedOutline(
                        outline = currentAttempt.outline,
                        diagnostics = currentAttempt.diagnostics,
                        fidelityReport = currentAttempt.fidelityReport,
                        corrections = currentAttempt.corrections,
                    )
                }
                previousFailure = conservationDropFeedback(attempt, drops)
                previousAttempt = currentAttempt
                continue
            }
            if (attempt < config.maxOutlineAttempts) {
                logger.warn(
                    "Outline attempt {}/{} failed fidelity check ({} issue(s)); retrying with diagnostic feedback",
                    attempt,
                    config.maxOutlineAttempts,
                    currentAttempt.fidelityReport.issues.size,
                )
            }
            previousFailure = currentAttempt.violations
            previousAttempt = currentAttempt
        }
        throw BpmnOutlineGenerationException(
            "Generated BPMN did not faithfully encode the source contract topology after " +
                "${config.maxOutlineAttempts} corrective attempt(s):" +
                "${System.lineSeparator()}$previousFailure",
        )
    }

    // Drops relative to the previous attempt, scoped to ids the driving
    // fidelity diagnostic did not name. Empty on attempt 1 (no previous attempt to compare
    // against) — `named` comes only from the ERROR issues that drove the retry, matching
    // `OutlineAttempt.violations`.
    private fun detectConservationDrops(
        contract: ProcessContract,
        previousAttempt: OutlineAttempt?,
        currentAttempt: OutlineAttempt,
    ): List<String> = previousAttempt?.let { prior ->
        OutlineConservation.detectDrops(
            named = prior.fidelityReport.issues
                .filter { it.severity == BpmnFidelitySeverity.ERROR }
                .flatMap { listOfNotNull(it.contractElementId, it.bpmnElementId) }
                .toSet(),
            contract = contract,
            previous = prior.outline.definition,
            next = currentAttempt.outline.definition,
        )
    }.orEmpty()

    private fun conservationDropFeedback(
        attempt: Int,
        drops: List<String>,
    ): String {
        logger.warn(
            "Outline attempt {}/{} passed the fidelity check but dropped {} previously-present" +
                " node(s)/field(s) the corrective feedback did not name: {}",
            attempt,
            config.maxOutlineAttempts,
            drops.size,
            drops.joinToString(),
        )
        return buildString {
            append("Attempt $attempt dropped the following, which the previous feedback did not")
            append(" ask you to change — restore them:")
            drops.forEach { append(System.lineSeparator()).append("- ").append(it) }
        }
    }

    /** One outline generation + fidelity-check pass within [createOutline]'s corrective loop. */
    private data class OutlineAttempt(
        val outline: ProcessOutline,
        val diagnostics: List<BpmnDiagnostic>,
        val fidelityReport: BpmnFidelityReport,
        val corrections: List<ContractCorrection>,
    ) {
        val violations: String
            get() = fidelityReport.issues
                .filter { it.severity == BpmnFidelitySeverity.ERROR }
                .joinToString(separator = System.lineSeparator()) { "- [${it.code}] ${it.message}" }
    }

    private fun attemptOutline(
        request: BpmnRequest,
        validatedContract: ValidatedProcessContract,
        promptRunner: PromptRunner,
        previousFailure: String?,
    ): OutlineAttempt {
        val flat = requestFlatDefinition(promptRunner, request, validatedContract, previousFailure)
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
        // Stamp every BPMN attribute the contract fully determines (default flows, branch
        // labels, multi-instance/loop markers, end/throw event kinds, gateway kind) onto the
        // raw definition BEFORE the fidelity check runs — the checks for those attributes fire
        // as ERROR and would otherwise abort the pipeline on an LLM slip the contract already
        // resolves. The repair engine also re-runs this pass on every repair candidate as a
        // second line of defence against LLM drift during refinement iterations.
        val conformance = conformancePort.conform(validatedContract.contract, rawDefinition)
        val definitionWithDefaults = conformance.definition
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
        return OutlineAttempt(outline, diagnostics, fidelityReport, conformance.corrections)
    }

    /**
     * Requests the raw [FlatBpmnDefinition] and translates only the framework's own
     * `InvalidLlmReturn*` (malformed/invalid model output) into [BpmnOutlineGenerationException],
     * a [com.embabel.agent.core.NonRetryable] signal. The caller's `flat.toSealed()` catch block —
     * which re-throws the framework's own `InvalidLlmReturnFormatException` for a structurally
     * incomplete node — sits outside this method and is untouched: only the genuine framework
     * parse/validation failure on the raw call is capped here.
     */
    private fun requestFlatDefinition(
        promptRunner: PromptRunner,
        request: BpmnRequest,
        validatedContract: ValidatedProcessContract,
        previousFailure: String?,
    ): FlatBpmnDefinition = try {
        eventPublisher.publishOnInvalidLlmReturn("authoring") {
            // Typed few-shot examples for the non-obvious topologies (fork/join, data, subprocesses, pools).
            val creating = GenerationExamples.all
                .fold(promptRunner.creating(FlatBpmnDefinition::class.java)) { acc, (label, example) ->
                    acc.withExample(label, example)
                }
            creating.fromTemplate("bpmner/generate_bpmn", templateModel(request, validatedContract, previousFailure))
        }
    } catch (e: InvalidLlmReturnFormatException) {
        throw BpmnOutlineGenerationException(
            "Outline generation model failed to produce a structured FlatBpmnDefinition: ${e.message}",
            e,
        )
    } catch (e: InvalidLlmReturnTypeException) {
        throw BpmnOutlineGenerationException(
            "Outline generation model returned an invalid FlatBpmnDefinition: ${e.message}",
            e,
        )
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
        eventPublisher.publishEvent(
            BpmnGraphComposedEvent(graph, corrections = outline.corrections, processId = AgentProcess.get()?.id),
        )
        return graph
    }

    override fun render(ready: ReadyBpmnContext, graph: LaidOutProcessGraph): RenderedBpmn {
        val rendered = renderer.render(graph)
        eventPublisher.publishEvent(BpmnGeneratedEvent(ready.request, rendered, processId = AgentProcess.get()?.id))
        return rendered
    }

    override fun render(graph: LaidOutProcessGraph): RenderedBpmn {
        return renderer.render(graph)
    }

    override fun startAsync(request: BpmnRequest): String {
        return agentInvoker.startAsync(request)
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
        previousFailure: String?,
    ): Map<String, Any> = mapOf(
        "contractJson" to jsonRenderer.render(validatedContract.contract),
        "processDescription" to request.processDescription,
        "styleGuide" to (request.styleGuide ?: ""),
        "targetLanguage" to (request.targetLanguage ?: ""),
        "previousFailure" to (previousFailure ?: ""),
        "namingShapeAdvice" to BpmnNamingShapeAdvice.allAdvice().map { advice ->
            val examples = advice.examples.joinToString(", ") { "\"$it\"" }
            val avoid = advice.antiExamples.joinToString(", ") { "\"$it\"" }
            "- ${advice.kind}: ${advice.shape}\n    examples: $examples\n    avoid:    $avoid"
        },
    )
}
