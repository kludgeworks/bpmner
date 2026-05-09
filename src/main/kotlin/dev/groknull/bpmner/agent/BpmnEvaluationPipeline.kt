package dev.groknull.bpmner.agent

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.min

@Component
class BpmnEvaluationPipeline(
    private val config: BpmnConfig,
    private val bpmnLintService: BpmnLintService,
    private val bpmnXsdValidator: BpmnXsdValidator,
    private val bpmnDefinitionValidator: BpmnDefinitionValidator,
    private val normalizer: BpmnDiagnosticNormalizer,
    private val fingerprints: BpmnFingerprintService,
) {
    private val logger = LoggerFactory.getLogger(BpmnEvaluationPipeline::class.java)

    fun evaluate(
        graph: LaidOutProcessGraph,
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        renderFailureMessage: String? = null,
        repairAttempts: Int,
    ): BpmnEvaluation {
        val diagnostics = mutableListOf<BpmnDiagnostic>()
        diagnostics.addAll(
            bpmnDefinitionValidator.validate(definition).map {
                normalizer.graphDiagnostic(graph, it)
            }
        )
        graph.validateOwnership().forEach { msg ->
            diagnostics += BpmnDiagnostic(
                source = BpmnDiagnosticSource.GRAPH,
                message = msg,
                repairScope = BpmnRepairScope.COMPOSITION,
            )
        }

        if (diagnostics.none { it.source == BpmnDiagnosticSource.GRAPH }) {
            if (renderFailureMessage != null || rendered == null) {
                diagnostics += normalizer.scopedDiagnostic(
                    graph = graph,
                    diagnostic = BpmnDiagnostic(
                        source = BpmnDiagnosticSource.RENDER,
                        message = renderFailureMessage ?: "Unknown BPMN rendering error",
                    )
                )
            } else {
                diagnostics.addAll(
                    normalizer.normalizeXsdDiagnostics(
                        issues = bpmnXsdValidator.validateDetailed(rendered.xml),
                        rendered = rendered,
                        graph = graph,
                    )
                )
                if (diagnostics.none { it.source == BpmnDiagnosticSource.XSD }) {
                    val lintIssues = bpmnLintService.lint(rendered.xml, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
                    if (lintIssues == null) {
                        logger.warn("bpmn-lint was unavailable; continuing without lint feedback")
                    } else {
                        diagnostics.addAll(normalizer.normalizeLintDiagnostics(lintIssues, rendered.elementIndex, graph))
                    }
                }
            }
        }

        val infrastructureDiagnostics = normalizer.infrastructureDiagnostics(diagnostics)
        if (infrastructureDiagnostics.isNotEmpty()) {
            logDiagnosticSummary(infrastructureDiagnostics)
            throw BpmnValidatorInfrastructureException(
                normalizer.validatorInfrastructureMessage(infrastructureDiagnostics)
            )
        }

        val globalDiagnostics = GlobalDiagnostics(diagnostics)
        if (diagnostics.isEmpty()) {
            logger.info(
                "Validation summary: graph=0, xsd=0, lint=0, repairScope=none, accepted=true, repairs={}",
                repairAttempts,
            )
        } else {
            logger.info(
                "Validation summary: graph={}, xsd={}, lint={}, repairScope={}, accepted=false, repairs={}",
                globalDiagnostics.countFor(BpmnDiagnosticSource.GRAPH),
                globalDiagnostics.countFor(BpmnDiagnosticSource.XSD),
                globalDiagnostics.countFor(BpmnDiagnosticSource.LINT),
                diagnostics.groupingBy { it.repairScope ?: BpmnRepairScope.FULL_PROCESS }.eachCount()
                    .entries.joinToString(",") { "${it.key.name.lowercase()}=${it.value}" },
                repairAttempts,
            )
        }
        logArtifactsIfEnabled(definition, rendered)

        return BpmnEvaluation(
            definition = definition,
            rendered = rendered,
            diagnostics = diagnostics,
            globalDiagnostics = globalDiagnostics,
            validatedXml = if (diagnostics.isEmpty()) rendered?.xml else null,
            renderFailureMessage = renderFailureMessage,
        )
    }

    fun toRecord(
        attempt: BpmnRepairAttempt,
        repairPromptFingerprint: String? = null,
    ): BpmnAttemptRecord {
        val globalDiagnostics = attempt.evaluation.globalDiagnostics
        return BpmnAttemptRecord(
            attemptNumber = attempt.attemptNumber,
            repairAttempts = attempt.repairAttempts,
            graphDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.GRAPH),
            renderDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.RENDER),
            xsdDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.XSD),
            lintDiagnostics = globalDiagnostics.countFor(BpmnDiagnosticSource.LINT),
            diagnosticFingerprint = fingerprints.diagnosticFingerprint(attempt.diagnostics),
            definitionFingerprint = fingerprints.definitionFingerprint(attempt.definition),
            repairPromptFingerprint = repairPromptFingerprint,
            topDiagnostics = attempt.diagnostics.take(TOP_DIAGNOSTICS_LIMIT).map { formatTopDiagnostic(it) },
        )
    }

    fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>) {
        logger.warn(
            "Diagnostic summary: total={}, graph={}, xsd={}, lint={}, scopes={}",
            diagnostics.size,
            diagnostics.count { it.source == BpmnDiagnosticSource.GRAPH },
            diagnostics.count { it.source == BpmnDiagnosticSource.XSD },
            diagnostics.count { it.source == BpmnDiagnosticSource.LINT },
            diagnostics.groupingBy { it.repairScope ?: BpmnRepairScope.FULL_PROCESS }.eachCount()
                .entries.joinToString(",") { "${it.key.name.lowercase()}=${it.value}" },
        )
        diagnostics.forEach { diagnostic ->
            logger.warn(
                "Diagnostic detail: source={}, rule={}, category={}, elementId={}, objectRef={}, repairScope={}, owner={}, message={}",
                diagnostic.source.name.lowercase(),
                diagnostic.rule ?: "-",
                diagnostic.category ?: "-",
                diagnostic.elementId ?: "-",
                diagnostic.objectRef ?: "-",
                diagnostic.repairScope?.name?.lowercase() ?: "-",
                diagnostic.ownerRef ?: "-",
                diagnostic.message,
            )
        }
    }

    private fun formatTopDiagnostic(diagnostic: BpmnDiagnostic): String = buildString {
        append(diagnostic.source.name.lowercase())
        diagnostic.rule?.let { append(" [$it]") }
        diagnostic.elementId?.let { append(" @${it}") }
        append(": ${diagnostic.message.take(120)}")
    }

    private fun logArtifactsIfEnabled(definition: BpmnDefinition, rendered: RenderedBpmn?) {
        if (!config.logging.dumpArtifacts) {
            return
        }
        logger.debug(
            "Artifact dump [definition]: {}",
            fingerprints.serializeDefinition(definition).truncate(config.logging.artifactPreviewLength),
        )
        rendered?.let {
            logger.debug("Artifact dump [renderedXml]: {}", it.xml.truncate(config.logging.artifactPreviewLength))
        }
    }

    private fun String.truncate(maxLength: Int): String {
        if (length <= maxLength) {
            return this
        }
        val kept = substring(0, min(length, maxLength))
        return "$kept…<truncated>"
    }

    companion object {
        private const val TOP_DIAGNOSTICS_LIMIT = 5
    }
}
