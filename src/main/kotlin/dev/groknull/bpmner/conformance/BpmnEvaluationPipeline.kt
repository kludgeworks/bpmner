/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.config.BpmnConfig
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import kotlin.math.min

@Service
@Component
internal class BpmnEvaluationPipeline(
    private val config: BpmnConfig,
    private val bpmnLintingPort: BpmnLintingPort,
    private val bpmnXsdValidationPort: BpmnXsdValidationPort,
    private val bpmnDefinitionValidator: BpmnDefinitionValidator,
    private val normalizer: BpmnDiagnosticNormalizer,
    private val fingerprints: BpmnFingerprintService,
) : BpmnValidator {
    private val logger = LoggerFactory.getLogger(BpmnEvaluationPipeline::class.java)

    override fun evaluate(
        graph: LaidOutProcessGraph,
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
        renderFailureMessage: String?,
        repairAttempts: Int,
    ): BpmnEvaluation {
        val diagnostics = mutableListOf<BpmnDiagnostic>()
        diagnostics.addAll(
            bpmnDefinitionValidator.validate(definition).map {
                normalizer.graphDiagnostic(graph, it)
            },
        )
        graph.validateOwnership().forEach { msg ->
            diagnostics +=
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = msg,
                    repairScope = BpmnRepairScope.COMPOSITION,
                )
        }

        val rawLintIssues =
            if (diagnostics.none { it.source == BpmnDiagnosticSource.GRAPH }) {
                collectRenderedDiagnostics(rendered, renderFailureMessage, graph, diagnostics)
            } else {
                null
            }

        val infrastructureDiagnostics = normalizer.infrastructureDiagnostics(diagnostics)
        if (infrastructureDiagnostics.isNotEmpty()) {
            logDiagnosticSummary(infrastructureDiagnostics)
            throw BpmnValidatorInfrastructureException(
                normalizer.validatorInfrastructureMessage(infrastructureDiagnostics),
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
                diagnostics
                    .groupingBy { it.repairScope ?: BpmnRepairScope.FULL_PROCESS }
                    .eachCount()
                    .entries
                    .joinToString(",") { "${it.key.name.lowercase()}=${it.value}" },
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
            rawLintIssues = rawLintIssues,
        )
    }

    private fun collectRenderedDiagnostics(
        rendered: RenderedBpmn?,
        renderFailureMessage: String?,
        graph: LaidOutProcessGraph,
        diagnostics: MutableList<BpmnDiagnostic>,
    ): List<LintIssue>? {
        if (renderFailureMessage != null || rendered == null) {
            diagnostics +=
                normalizer.scopedDiagnostic(
                    graph = graph,
                    diagnostic =
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.RENDER,
                        message = renderFailureMessage ?: "Unknown BPMN rendering error",
                    ),
                )
            return null
        }
        diagnostics.addAll(
            normalizer.normalizeXsdDiagnostics(
                issues = bpmnXsdValidationPort.validateDetailed(rendered.xml),
                rendered = rendered,
                graph = graph,
            ),
        )
        if (diagnostics.any { it.source == BpmnDiagnosticSource.XSD }) {
            return null
        }
        val lintIssues = bpmnLintingPort.lint(rendered.definition)
        if (lintIssues == null) {
            logger.warn("Rule evaluator was unavailable; continuing without rule feedback")
            return null
        }
        diagnostics.addAll(normalizer.normalizeLintDiagnostics(lintIssues, rendered.elementIndex, graph))
        return lintIssues
    }

    override fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>) {
        logger.warn(
            "Diagnostic summary: total={}, graph={}, xsd={}, lint={}, scopes={}",
            diagnostics.size,
            diagnostics.count { it.source == BpmnDiagnosticSource.GRAPH },
            diagnostics.count { it.source == BpmnDiagnosticSource.XSD },
            diagnostics.count { it.source == BpmnDiagnosticSource.LINT },
            diagnostics
                .groupingBy { it.repairScope ?: BpmnRepairScope.FULL_PROCESS }
                .eachCount()
                .entries
                .joinToString(",") { "${it.key.name.lowercase()}=${it.value}" },
        )
        diagnostics.forEach { diagnostic ->
            logger.warn(
                "Diagnostic detail: source={}, rule={}, severity={}, elementId={}, " +
                    "objectRef={}, repairScope={}, owner={}, message={}",
                diagnostic.source.name.lowercase(),
                diagnostic.rule ?: "-",
                diagnostic.severity.name.lowercase(),
                diagnostic.elementId ?: "-",
                diagnostic.objectRef ?: "-",
                diagnostic.repairScope?.name?.lowercase() ?: "-",
                diagnostic.ownerRef ?: "-",
                diagnostic.message,
            )
        }
    }

    private fun logArtifactsIfEnabled(
        definition: BpmnDefinition,
        rendered: RenderedBpmn?,
    ) {
        if (!config.logging.dumpArtifacts) return
        logger.debug(
            "Artifact dump [definition]: {}",
            fingerprints.serializeDefinition(definition).truncate(config.logging.artifactPreviewLength),
        )
        rendered?.let {
            logger.debug("Artifact dump [renderedXml]: {}", it.xml.truncate(config.logging.artifactPreviewLength))
        }
    }

    private fun String.truncate(maxLength: Int): String {
        if (length <= maxLength) return this
        return "${substring(0, min(length, maxLength))}…<truncated>"
    }
}
