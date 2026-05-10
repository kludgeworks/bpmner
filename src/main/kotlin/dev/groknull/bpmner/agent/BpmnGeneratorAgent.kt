package dev.groknull.bpmner.agent

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.core.ActionRetryPolicy
import org.slf4j.LoggerFactory
import java.io.File

@Agent(description = "Generate a valid BPMN 2.0 diagram from a plain-language business process description")
class BpmnGeneratorAgent(
    private val config: BpmnConfig,
    private val bpmnConverter: BpmnDefinitionToXmlConverter,
    private val refinementEngine: BpmnRefinementEngine,
    private val layoutService: BpmnLayoutService,
    private val bpmnLintService: BpmnLintService,
    private val bpmnXsdValidator: BpmnXsdValidator,
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

    @Action(
        description = "Validate rendered BPMN, repair the typed definition if needed, and return validated BPMN XML",
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE,
    )
    fun validateAndRefineBpmn(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml = try {
        refinementEngine.refine(request, graph, rendered, context)
    } catch (e: BpmnRefinementFailureException) {
        logger.warn(e.summary)
        throw e
    }

    @Action(description = "Apply auto-layout to the auto-fixed BPMN XML")
    fun layoutBpmnXml(bpmn: AutoFixedBpmnXml): LayoutedBpmnXml {
        val layoutedXml = layoutService.layout(bpmn.xml)
        return LayoutedBpmnXml(xml = layoutedXml)
    }

    @Action(description = "Apply bounded deterministic XML auto-fixes before layout")
    fun autoFixBpmnXml(bpmn: ValidatedBpmnXml): AutoFixedBpmnXml {
        val lintIssues = bpmnLintService.lint(bpmn.xml, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
        if (lintIssues == null) {
            logger.warn("BPMN XML auto-fix skipped because bpmn-lint validation was unavailable")
            return AutoFixedBpmnXml(xml = bpmn.xml)
        }

        val autoFixResult = bpmnLintService.autoFix(bpmn.xml, lintIssues, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
        if (autoFixResult == null) {
            logger.warn("BPMN XML auto-fix was unavailable; keeping validated XML")
            return AutoFixedBpmnXml(xml = bpmn.xml)
        }

        if (autoFixResult.applied.isNotEmpty() || autoFixResult.skipped.isNotEmpty() || autoFixResult.errors.isNotEmpty()) {
            logger.info(
                "BPMN XML auto-fix result: changed={}, applied={}, skipped={}, errors={}",
                autoFixResult.changed,
                autoFixResult.applied.joinToString("; ") { it.summary() },
                autoFixResult.skipped.joinToString("; ") { it.summary() },
                autoFixResult.errors.joinToString("; ") { it.summary() },
            )
        }

        if (autoFixResult.changed) {
            val xsdIssues = bpmnXsdValidator.validateDetailed(autoFixResult.xml)
            if (xsdIssues.isNotEmpty()) {
                logger.warn(
                    "BPMN XML auto-fix produced XSD-invalid XML; keeping validated XML. applied={}, skipped={}, errors={}, xsdErrors={}",
                    autoFixResult.applied.joinToString("; ") { it.summary() },
                    autoFixResult.skipped.joinToString("; ") { it.summary() },
                    autoFixResult.errors.joinToString("; ") { it.summary() },
                    xsdIssues.joinToString("; ") { it.summary() },
                )
                return AutoFixedBpmnXml(
                    xml = bpmn.xml,
                    autoFixResult = autoFixResult,
                )
            }
        }

        return AutoFixedBpmnXml(
            xml = if (autoFixResult.changed) autoFixResult.xml else bpmn.xml,
            autoFixResult = autoFixResult,
        )
    }

    @Action(description = "Validate the final layouted BPMN XML without semantic repair")
    fun validateFinalBpmnXml(bpmn: LayoutedBpmnXml): FinalValidatedBpmnXml {
        val diagnostics = mutableListOf<BpmnDiagnostic>()
        diagnostics += bpmnXsdValidator.validateDetailed(bpmn.xml).map { issue ->
            BpmnDiagnostic(
                source = BpmnDiagnosticSource.XSD,
                message = issue.message,
                elementId = issue.elementId,
                repairScope = BpmnRepairScope.FULL_PROCESS,
            )
        }

        if (diagnostics.none { it.source == BpmnDiagnosticSource.XSD }) {
            val lintIssues = bpmnLintService.lint(bpmn.xml, BpmnLintPhase.FINAL_POST_LAYOUT)
            if (lintIssues == null) {
                logger.warn("Final bpmn-lint validation was unavailable; continuing without lint feedback")
            } else {
                diagnostics += lintIssues.map { issue ->
                    val isLayoutDiagnostic = issue.rule.isLayoutSensitiveLintRule()
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.LINT,
                        message = issue.message,
                        rule = issue.rule,
                        category = issue.category,
                        elementId = issue.id,
                        repairScope = if (isLayoutDiagnostic) BpmnRepairScope.LAYOUT else BpmnRepairScope.FULL_PROCESS,
                    )
                }
            }
        }

        if (diagnostics.isNotEmpty()) {
            throw BpmnFinalValidationException(finalValidationMessage(diagnostics))
        }

        logger.info("Final BPMN validation passed after auto-layout")
        return FinalValidatedBpmnXml(xml = bpmn.xml)
    }

    fun validateFinalLayoutedBpmnXml(bpmn: LayoutedBpmnXml): FinalValidatedBpmnXml =
        validateFinalBpmnXml(bpmn)

    fun validateAndRefineBpmn(
        request: BpmnRequest,
        rendered: RenderedBpmn,
        context: ActionContext,
    ): ValidatedBpmnXml {
        val graph = rendered.sourceGraph ?: run {
            val outline = ProcessOutline(
                request = request,
                definition = rendered.definition,
                metrics = outlineMetrics(rendered.definition),
            )
            val validatedOutline = validateOutline(outline)
            val phasePlans = generatePhasePlans(validatedOutline)
            val validatedPhasePlans = validatePhasePlans(phasePlans)
            val composedGraph = composeProcessGraph(validatedPhasePlans)
            val ownedGraph = assignOwnership(composedGraph)
            assignLayout(ownedGraph)
        }
        return try {
            refinementEngine.refine(request, graph, rendered.copy(sourceGraph = graph), context)
        } catch (e: BpmnRefinementFailureException) {
            logger.warn(e.summary)
            throw e
        }
    }

    @AchievesGoal(
        description = "Write validated BPMN 2.0 XML to the requested output file",
        export = Export(name = "generateBpmn", remote = true, startingInputTypes = [BpmnRequest::class]),
    )
    @Action(description = "Write the layouted BPMN XML to disk")
    fun writeBpmn(request: BpmnRequest, bpmn: FinalValidatedBpmnXml): BpmnResult {
        File(request.outputFile).writeText(bpmn.xml, Charsets.UTF_8)
        logger.info(
            "Final BPMN summary: layout applied, finalXmlLength={}, outputFile={}",
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

    private fun finalValidationMessage(diagnostics: List<BpmnDiagnostic>): String = buildString {
        append("Final BPMN validation failed after auto-layout")
        val layoutDiagnostics = diagnostics.filter { it.repairScope == BpmnRepairScope.LAYOUT }
        if (layoutDiagnostics.isNotEmpty()) {
            append("; layout diagnostics remain after auto-layout")
        }
        append(": ")
        append(
            diagnostics.groupingBy { it.source }.eachCount()
                .entries.joinToString(",") { "${it.key.name.lowercase()}=${it.value}" }
        )
        appendLine()
        diagnostics.forEach { diagnostic ->
            append("- source=${diagnostic.source.name.lowercase()}")
            diagnostic.rule?.let { append(", rule=$it") }
            diagnostic.elementId?.let { append(", elementId=$it") }
            diagnostic.repairScope?.let { append(", repairScope=${it.name.lowercase()}") }
            appendLine(": ${diagnostic.message}")
        }
    }.trim()

    private fun String.isLayoutSensitiveLintRule(): Boolean =
        this == "no-overlapping-elements" || this == "bpmnlint/no-overlapping-elements"

    private fun BpmnAutoFixChange.summary(): String =
        listOfNotNull(rule, elementId, message).joinToString("|")

    private fun BpmnAutoFixSkip.summary(): String =
        listOfNotNull(rule, elementId, message).joinToString("|")

    private fun BpmnAutoFixError.summary(): String =
        listOfNotNull(rule, elementId, message).joinToString("|")

    private fun XsdValidationIssue.summary(): String =
        listOfNotNull(elementId, message).joinToString("|")
}

class BpmnFinalValidationException(message: String) : IllegalStateException(message)
