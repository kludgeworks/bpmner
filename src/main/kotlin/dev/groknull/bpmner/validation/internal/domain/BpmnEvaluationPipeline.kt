/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnEvaluation
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.BpmnValidator
import dev.groknull.bpmner.validation.BpmnValidatorInfrastructureException
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.GlobalDiagnostics
import dev.groknull.bpmner.validation.LintIssue
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
            logger.debug(
                "Validation summary: graph=0, xsd=0, lint=0, repairScope=none, accepted=true, repairs={}",
                repairAttempts,
            )
        } else {
            logger.debug(
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
        val lintIssues = bpmnLintingPort.lint(rendered.xml, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
        if (lintIssues == null) {
            logger.debug("bpmn-lint was unavailable; continuing without lint feedback")
            return null
        }
        diagnostics.addAll(normalizer.normalizeLintDiagnostics(lintIssues, rendered.elementIndex, graph))
        return lintIssues
    }

    override fun logDiagnosticSummary(diagnostics: List<BpmnDiagnostic>) {
        logger.debug(
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
            logger.debug(
                "Diagnostic detail: source={}, rule={}, category={}, elementId={}, " +
                    "objectRef={}, repairScope={}, owner={}, message={}",
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
