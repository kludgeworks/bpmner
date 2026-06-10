/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.support.InvalidLlmReturnFormatException
import dev.groknull.bpmner.alignment.AlignedBpmnXml
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.format
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.MAIN_PHASE_OWNER
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnContractFidelityChecker
import dev.groknull.bpmner.generation.BpmnFidelitySeverity
import dev.groknull.bpmner.generation.BpmnGeneratedEvent
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnRenderer
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.DefaultFlowAssigner
import dev.groknull.bpmner.generation.FlatBpmnDefinition
import dev.groknull.bpmner.generation.ProcessOutline
import dev.groknull.bpmner.generation.ValidatedOutline
import dev.groknull.bpmner.generation.internal.adapter.inbound.BpmnGeneratorPromptFactory
import dev.groknull.bpmner.generation.toSealed
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnRepairScope
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.io.File

@Application
@Agent(description = "Generate a valid BPMN 2.0 diagram from a plain-language workflow description")
internal class BpmnGeneratorAgent(
    private val config: BpmnConfig,
    private val bpmnConverter: BpmnRenderer,
    private val metricsCalculator: BpmnGeneratorMetrics,
    private val fidelityChecker: BpmnContractFidelityChecker,
    private val defaultFlowAssigner: DefaultFlowAssigner,
    private val eventPublisher: ApplicationEventPublisher,
    private val promptFactory: BpmnGeneratorPromptFactory,
) {
    private val logger = LoggerFactory.getLogger(BpmnGeneratorAgent::class.java)

    /**
     * Single LLM-driven action: contract → outline → fidelity-checked [ValidatedOutline].
     *
     * Phase 5 (#220) collapsed the previous two-action shape (`createProcessOutline` + `validateOutline`)
     * because the LLM call and its deterministic post-validation are one logical step. The
     * `?: error("Outline generator failed…")` defense that lived on the prior `createObject` call is
     * gone with the inline — `createObject` returns non-null by Embabel's contract and throws
     * `InvalidLlmReturnFormatException` on failure (see [Embabel `LlmOperations.createObject`]).
     */
    @Action(
        description =
        "Create and validate a process outline from a validated process contract",
    )
    fun createOutline(
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
        val creating = promptFactory.generationExamples()
            .fold(promptRunner.creating(FlatBpmnDefinition::class.java)) { acc, (label, example) ->
                acc.withExample(label, example)
            }
        val flat = creating.fromTemplate("bpmner/generate_bpmn", promptFactory.templateModel(request, validatedContract))
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

    /**
     * Single deterministic action: outline → composed graph + ownership + layout → [LaidOutProcessGraph].
     *
     * Phase 5 (#220) collapsed the previous five-action chain
     * (`generatePhasePlans` + `validatePhasePlans` + `composeProcessGraph` + `assignOwnership` + `assignLayout`)
     * because each step was a deterministic structural derivation of the previous one. The
     * intermediate types [ComposedProcessGraph] and [OwnedElementGraph] survive — they are the
     * repair-agent's inbound contract surface (PR #274) — but they no longer cross an action
     * boundary. Only [LaidOutProcessGraph] does. Repair still reads `OwnedElementGraph` via
     * `LaidOutProcessGraph.ownedGraph`.
     */
    @Action(description = "Compose the validated outline into a fully laid-out process graph")
    fun composeGraph(outline: ValidatedOutline): LaidOutProcessGraph {
        val definition = outline.definition

        // composeProcessGraph: object-ref ownership map (single-phase pipeline → one owner).
        val objectOwners =
            buildMap {
                put("process", MAIN_PHASE_OWNER)
                definition.nodes.forEach { put("nodes[id=${it.id}]", MAIN_PHASE_OWNER) }
                definition.sequences.forEach { put("sequences[id=${it.id}]", MAIN_PHASE_OWNER) }
            }
        val composed =
            ComposedProcessGraph(
                definition = definition,
                objectOwnersByObjectRef = objectOwners,
            )
        logger.info(
            "Composition summary: nodes={}, edges={}, subprocesses={}",
            definition.nodes.size,
            definition.sequences.size,
            outline.outline.metrics.subprocessCount,
        )

        // assignOwnership: element-id ownership map (mirrors objectOwners plus `_di` diagram-element IDs).
        // `getValue` not `[...] ?: MAIN_PHASE_OWNER` — `objectOwners` was just populated above with
        // every key we look up here, so a missing entry would mean a logic bug in this function
        // and should fail loudly with NoSuchElementException rather than silently substitute.
        val elementOwners =
            buildMap {
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
        val owned =
            OwnedElementGraph(
                composedGraph = composed,
                elementOwnersByElementId = elementOwners,
                objectOwnersByObjectRef = objectOwners,
            )

        // assignLayout: trivial wrap.
        return LaidOutProcessGraph(ownedGraph = owned, definition = definition)
    }

    @Action(description = "Render a laid out BPMN process graph into BPMN 2.0 XML with stable element linkage")
    fun renderBpmnXml(
        ready: ReadyBpmnContext,
        graph: LaidOutProcessGraph,
    ): RenderedBpmn {
        val request = ready.request
        val rendered = bpmnConverter.render(graph)
        logArtifactDump("rendered-bpmn-xml", rendered.xml)
        eventPublisher.publishEvent(BpmnGeneratedEvent(request, rendered))
        return rendered
    }

    @AchievesGoal(
        description = "Return validated BPMN 2.0 XML and optionally write it to the requested output file",
        export =
        Export(
            name = "generateBpmn",
            remote = true,
            startingInputTypes = [com.embabel.agent.domain.io.UserInput::class, BpmnRequest::class],
        ),
    )
    @Action(description = "Return the layouted BPMN XML and write to disk if requested")
    fun finalizeBpmn(
        ready: ReadyBpmnContext,
        bpmn: AlignedBpmnXml,
    ): BpmnResult {
        val request = ready.request
        if (request.outputFile != null) {
            File(request.outputFile).writeText(bpmn.xml, Charsets.UTF_8)
            logger.info(
                "Final BPMN summary: layout verified, finalXmlLength={}, outputFile={}",
                bpmn.xml.length,
                request.outputFile,
            )
        } else {
            logger.info(
                "Final BPMN summary: layout verified, finalXmlLength={}, (no output file requested)",
                bpmn.xml.length,
            )
        }

        return BpmnResult(
            outputFile = request.outputFile,
            status = BpmnGenerationStatus.GENERATED,
            xml = bpmn.xml,
            alignmentReport = bpmn.alignmentReport,
        )
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
}
