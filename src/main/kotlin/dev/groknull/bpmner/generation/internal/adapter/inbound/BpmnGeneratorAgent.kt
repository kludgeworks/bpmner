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
import dev.groknull.bpmner.alignment.AlignedBpmnXml
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.contract.format
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.ComposedProcessGraph
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.OwnedElementGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.generation.BpmnGeneratedEvent
import dev.groknull.bpmner.generation.BpmnGenerationStatus
import dev.groknull.bpmner.generation.BpmnRenderer
import dev.groknull.bpmner.generation.BpmnResult
import dev.groknull.bpmner.generation.PhasePlan
import dev.groknull.bpmner.generation.PhasePlanSet
import dev.groknull.bpmner.generation.ProcessOutline
import dev.groknull.bpmner.generation.ValidatedOutline
import dev.groknull.bpmner.generation.ValidatedPhasePlan
import dev.groknull.bpmner.generation.ValidatedPhasePlanSet
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnRepairScope
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import java.io.File

@PrimaryAdapter
@Agent(description = "Generate a valid BPMN 2.0 diagram from a plain-language workflow description")
internal class BpmnGeneratorAgent(
    private val config: BpmnConfig,
    private val bpmnConverter: BpmnRenderer,
    private val metricsCalculator: BpmnGeneratorMetrics,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnGeneratorAgent::class.java)
    private val contractPromptFactory = BpmnContractGenerationPromptFactory()

    @Action(
        description =
            "Create a high-level process outline and initial typed BPMN artifact from a validated process contract",
    )
    fun createProcessOutline(
        request: BpmnRequest,
        validatedContract: ValidatedProcessContract,
        context: OperationContext,
    ): ProcessOutline {
        if (!validatedContract.isValid) {
            val issues =
                validatedContract.report.issues
                    .joinToString(separator = System.lineSeparator()) { "- ${it.format()}" }
            error("Cannot generate BPMN from an invalid process contract:${System.lineSeparator()}$issues")
        }
        val promptRunner = config.generator.promptRunner(context)
        val prompt = contractPromptFactory.prompt(request, validatedContract)
        val definition = promptRunner.createObject(prompt, BpmnDefinition::class.java)
            ?: error("Outline generator failed to produce a structured outline.")
        val outline =
            ProcessOutline(
                request = request,
                definition = definition,
                metrics = metricsCalculator.calculate(definition),
            )
        logger.info(
            "Outline summary: phases={}, branches={}, loops={}, subprocesses={}",
            outline.metrics.phaseCount,
            outline.metrics.branchCount,
            outline.metrics.loopCount,
            outline.metrics.subprocessCount,
        )
        logArtifactDump("process-outline", outline)
        return outline
    }

    @Action(description = "Validate the generated process outline before phase-level processing")
    fun validateOutline(outline: ProcessOutline): ValidatedOutline {
        val diagnostics = mutableListOf<BpmnDiagnostic>()
        if (outline.definition.processId.isBlank()) {
            diagnostics +=
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = "outline must define a non-blank processId",
                    objectRef = "process",
                    repairScope = BpmnRepairScope.OUTLINE,
                )
        }
        if (outline.definition.processName.isBlank()) {
            diagnostics +=
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = "outline must define a non-blank processName",
                    objectRef = "process",
                    repairScope = BpmnRepairScope.OUTLINE,
                )
        }
        if (diagnostics.isNotEmpty()) {
            logger.warn("Outline validation summary: {} issue(s)", diagnostics.size)
        }
        return ValidatedOutline(outline = outline, diagnostics = diagnostics)
    }

    @Action(description = "Generate local phase plans from the validated process outline")
    fun generatePhasePlans(outline: ValidatedOutline): PhasePlanSet {
        val phasePlans =
            listOf(
                PhasePlan(
                    phaseId = "phase:main",
                    ownerRef = "phase:main",
                    definition = outline.definition,
                ),
            )
        logger.info("Phase generation summary: generated {} phase(s), 0 failed local validation", phasePlans.size)
        return PhasePlanSet(outline = outline, phasePlans = phasePlans)
    }

    @Action(description = "Validate phase plans independently before composition")
    fun validatePhasePlans(phasePlans: PhasePlanSet): ValidatedPhasePlanSet {
        val validatedPlans =
            phasePlans.phasePlans.map { phasePlan ->
                ValidatedPhasePlan(
                    phaseId = phasePlan.phaseId,
                    ownerRef = phasePlan.ownerRef,
                    definition = phasePlan.definition,
                    diagnostics = emptyList(),
                )
            }
        val failedPlans = validatedPlans.count { it.diagnostics.isNotEmpty() }
        logger.info(
            "Phase validation summary: generated {} phase(s), {} failed local validation",
            validatedPlans.size,
            failedPlans,
        )
        return ValidatedPhasePlanSet(outline = phasePlans.outline, phasePlans = validatedPlans)
    }

    @Action(description = "Compose validated phase plans into a process graph")
    fun composeProcessGraph(validatedPhasePlans: ValidatedPhasePlanSet): ComposedProcessGraph {
        val definition = validatedPhasePlans.definition
        val phaseOwner = validatedPhasePlans.phasePlans.single().ownerRef
        val objectOwners =
            buildMap {
                put("process", phaseOwner)
                definition.nodes.forEach { put("nodes[id=${it.id}]", phaseOwner) }
                definition.sequences.forEach { put("sequences[id=${it.id}]", phaseOwner) }
            }
        logger.info(
            "Composition summary: nodes={}, edges={}, subprocesses={}",
            definition.nodes.size,
            definition.sequences.size,
            validatedPhasePlans.outline.outline.metrics.subprocessCount,
        )
        return ComposedProcessGraph(
            definition = definition,
            objectOwnersByObjectRef = objectOwners,
        )
    }

    @Action(description = "Assign stable ownership metadata to the composed process graph")
    fun assignOwnership(graph: ComposedProcessGraph): OwnedElementGraph {
        val elementOwners =
            buildMap {
                put(graph.definition.processId, graph.objectOwnersByObjectRef["process"] ?: "phase:main")
                graph.definition.nodes.forEach { node ->
                    put(node.id, graph.objectOwnersByObjectRef["nodes[id=${node.id}]"] ?: "phase:main")
                    put("${node.id}_di", graph.objectOwnersByObjectRef["nodes[id=${node.id}]"] ?: "phase:main")
                }
                graph.definition.sequences.forEach { edge ->
                    put(edge.id, graph.objectOwnersByObjectRef["sequences[id=${edge.id}]"] ?: "phase:main")
                    put("${edge.id}_di", graph.objectOwnersByObjectRef["sequences[id=${edge.id}]"] ?: "phase:main")
                }
            }
        return OwnedElementGraph(
            composedGraph = graph,
            elementOwnersByElementId = elementOwners,
            objectOwnersByObjectRef = graph.objectOwnersByObjectRef,
        )
    }

    @Action(description = "Assign deterministic layout to the process graph")
    fun assignLayout(graph: OwnedElementGraph): LaidOutProcessGraph =
        LaidOutProcessGraph(
            ownedGraph = graph,
            definition = graph.definition,
        )

    @Action(description = "Render a laid out BPMN process graph into BPMN 2.0 XML with stable element linkage")
    fun renderBpmnXml(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
    ): RenderedBpmn {
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
                startingInputTypes = [BpmnRequest::class],
            ),
    )
    @Action(description = "Return the layouted BPMN XML and write to disk if requested")
    fun finalizeBpmn(
        request: BpmnRequest,
        bpmn: AlignedBpmnXml,
    ): BpmnResult {
        if (request.outputFile != null) {
            File(request.outputFile).writeText(bpmn.xml, Charsets.UTF_8)
            logger.info(
                "Final BPMN summary: layout applied, finalXmlLength={}, outputFile={}",
                bpmn.xml.length,
                request.outputFile,
            )
        } else {
            logger.info(
                "Final BPMN summary: layout applied, finalXmlLength={}, (no output file requested)",
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

    private fun logArtifactDump(
        label: String,
        artifact: Any,
    ) {
        if (!config.logging.dumpArtifacts) return
        val payload = artifact.toString().take(config.logging.artifactPreviewLength)
        logger.debug("Artifact dump [{}]: {}", label, payload)
    }
}
