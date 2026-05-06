package dev.groknull.bpmner.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import org.slf4j.LoggerFactory
import java.io.File

@Agent(description = "Generate a valid BPMN 2.0 diagram from a plain-language business process description")
class BpmnGeneratorAgent(
    private val config: BpmnConfig,
    private val bpmnConverter: BpmnDefinitionToXmlConverter,
    private val refinementWorkflow: BpmnRefinementWorkflow,
) {
    private val logger = LoggerFactory.getLogger(BpmnGeneratorAgent::class.java)

    @Action(description = "Create a high-level process outline and initial typed BPMN artifact from a business-process description")
    fun createProcessOutline(request: BpmnRequest, context: OperationContext): ProcessOutline {
        val promptRunner = promptRunner(context, request)
        val definition = promptRunner.createObject(request.generationPrompt(), BpmnDefinition::class.java)
        val outline = ProcessOutline(
            request = request,
            definition = definition,
            metrics = outlineMetrics(definition),
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
            diagnostics += BpmnDiagnostic(
                source = BpmnDiagnosticSource.GRAPH,
                message = "outline must define a non-blank processId",
                objectRef = "process",
                repairScope = BpmnRepairScope.OUTLINE,
            )
        }
        if (outline.definition.processName.isBlank()) {
            diagnostics += BpmnDiagnostic(
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
        val phasePlans = listOf(
            PhasePlan(
                phaseId = "phase:main",
                ownerRef = "phase:main",
                definition = outline.definition,
            )
        )
        logger.info("Phase generation summary: generated {} phase(s), 0 failed local validation", phasePlans.size)
        return PhasePlanSet(outline = outline, phasePlans = phasePlans)
    }

    @Action(description = "Validate phase plans independently before composition")
    fun validatePhasePlans(phasePlans: PhasePlanSet): ValidatedPhasePlanSet {
        val validatedPlans = phasePlans.phasePlans.map { phasePlan ->
            val diagnostics = emptyList<BpmnDiagnostic>()
            ValidatedPhasePlan(
                phaseId = phasePlan.phaseId,
                ownerRef = phasePlan.ownerRef,
                definition = phasePlan.definition,
                diagnostics = diagnostics,
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
        val objectOwners = buildMap {
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
            outline = validatedPhasePlans.outline,
            definition = definition,
            objectOwnersByObjectRef = objectOwners,
        )
    }

    @Action(description = "Assign stable ownership metadata to the composed process graph")
    fun assignOwnership(graph: ComposedProcessGraph): OwnedElementGraph {
        val elementOwners = buildMap {
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
    fun assignLayout(graph: OwnedElementGraph): LaidOutProcessGraph = LaidOutProcessGraph(
        ownedGraph = graph,
        definition = graph.definition,
    )

    @Action(description = "Render a laid out BPMN process graph into BPMN 2.0 XML with stable element linkage")
    fun renderBpmnXml(graph: LaidOutProcessGraph): RenderedBpmn {
        val rendered = bpmnConverter.render(graph)
        logArtifactDump("rendered-bpmn-xml", rendered.xml)
        return rendered
    }

    @Action(description = "Validate rendered BPMN, repair the typed definition if needed, and return validated BPMN XML")
    fun validateAndRefineBpmn(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml = refinementWorkflow.refine(request, graph, rendered, context)

    fun validateAndRefineBpmn(
        request: BpmnRequest,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml {
        val graph = rendered.sourceGraph ?: assignLayout(
            assignOwnership(
                composeProcessGraph(
                    validatePhasePlans(
                        generatePhasePlans(
                            validateOutline(
                                ProcessOutline(
                                    request = request,
                                    definition = rendered.definition,
                                    metrics = outlineMetrics(rendered.definition),
                                )
                            )
                        )
                    )
                )
            )
        )
        return refinementWorkflow.refine(request, graph, rendered.copy(sourceGraph = graph), context)
    }

    @AchievesGoal(
        description = "Write validated BPMN 2.0 XML to the requested output file",
        export = Export(name = "generateBpmn", remote = true, startingInputTypes = [BpmnRequest::class]),
    )
    @Action(description = "Write the validated BPMN XML to disk")
    fun writeBpmn(request: BpmnRequest, bpmn: ValidatedBpmnXml): BpmnResult {
        File(request.outputFile).writeText(bpmn.xml, Charsets.UTF_8)
        logger.info(
            "Final BPMN summary: accepted after {} scoped repair attempt(s), finalXmlLength={}, outputFile={}",
            bpmn.repairAttempts,
            bpmn.xml.length,
            request.outputFile,
        )
        return BpmnResult(outputFile = request.outputFile, xml = bpmn.xml)
    }

    private fun promptRunner(context: OperationContext, request: BpmnRequest) =
        config.generator.promptRunner(context).withPromptContributor(request)

    private fun outlineMetrics(definition: BpmnDefinition): OutlineMetrics = OutlineMetrics(
        phaseCount = 1,
        branchCount = definition.nodes.count { it.type == NodeType.EXCLUSIVE_GATEWAY },
        loopCount = definition.sequences.count { it.sourceRef == it.targetRef },
        subprocessCount = 0,
    )

    private fun logArtifactDump(label: String, artifact: Any) {
        if (!config.logging.dumpArtifacts) {
            return
        }
        val payload = artifact.toString().take(config.logging.artifactPreviewLength)
        logger.debug("Artifact dump [{}]: {}", label, payload)
    }
}
