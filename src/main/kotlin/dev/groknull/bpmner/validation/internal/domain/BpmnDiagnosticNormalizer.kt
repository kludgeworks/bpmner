package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnRepairScope
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.RepairKind
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.core.format
import dev.groknull.bpmner.validation.BpmnLintingPort
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Service
@Component
internal class BpmnDiagnosticNormalizer(
    private val lintingPort: BpmnLintingPort,
) {
    private val logger = LoggerFactory.getLogger(BpmnDiagnosticNormalizer::class.java)

    fun normalizeXsdDiagnostics(
        issues: List<XsdValidationIssue>,
        rendered: RenderedBpmn,
        graph: LaidOutProcessGraph,
    ): List<BpmnDiagnostic> =
        issues.map { issue ->
            val elementId = issue.elementId?.takeIf { rendered.elementIndex.knownElementIds().contains(it) }
            scopedDiagnostic(
                graph = graph,
                diagnostic =
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.XSD,
                        message = issue.message,
                        elementId = elementId,
                        objectRef = rendered.elementIndex.objectRefForElementId(elementId),
                    ),
            )
        }

    fun normalizeLintDiagnostics(
        lintIssues: List<LintIssue>,
        elementIndex: dev.groknull.bpmner.core.BpmnElementIndex,
        graph: LaidOutProcessGraph,
    ): List<BpmnDiagnostic> {
        val caps = lintingPort.lintRuleCapabilities()
        return lintIssues.map { issue ->
            val elementId = issue.id?.takeIf { elementIndex.knownElementIds().contains(it) }
            val bareId = issue.rule?.replace("^(bpmner|bpmnlint-plugin-bpmner)/".toRegex(), "")
            val cap = bareId?.let { caps[it] }
            scopedDiagnostic(
                graph = graph,
                diagnostic =
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.LINT,
                        message = issue.message,
                        rule = issue.rule,
                        category = issue.category,
                        elementId = elementId,
                        objectRef = elementIndex.objectRefForElementId(elementId),
                        kind = cap?.kind ?: RepairKind.LLM_MODEL_PATCH,
                        repairSafety = cap?.repairSafety,
                        fixHandler = cap?.fixHandler,
                    ),
            )
        }
    }

    fun graphDiagnostic(
        graph: LaidOutProcessGraph,
        message: String,
    ): BpmnDiagnostic =
        scopedDiagnostic(
            graph = graph,
            diagnostic =
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.GRAPH,
                    message = message,
                ),
        )

    fun scopedDiagnostic(
        graph: LaidOutProcessGraph,
        diagnostic: BpmnDiagnostic,
    ): BpmnDiagnostic {
        val ownerRef =
            diagnostic.ownerRef
                ?: graph.ownerForObjectRef(diagnostic.objectRef)
                ?: graph.ownerForElementId(diagnostic.elementId)
        val repairScope = diagnostic.repairScope ?: inferRepairScope(diagnostic, ownerRef)
        if (diagnostic.elementId != null && ownerRef == null && diagnostic.source != BpmnDiagnosticSource.RENDER) {
            logger.debug(
                "Ownership unresolved for elementId={}, objectRef={}, source={}, inferredRepairScope={}",
                diagnostic.elementId,
                diagnostic.objectRef ?: "-",
                diagnostic.source.name.lowercase(),
                repairScope.name.lowercase(),
            )
        }
        return diagnostic.copy(repairScope = repairScope, ownerRef = ownerRef)
    }

    fun infrastructureDiagnostics(diagnostics: List<BpmnDiagnostic>): List<BpmnDiagnostic> =
        diagnostics.filter { it.isValidatorInfrastructureFailure() }

    fun validatorInfrastructureMessage(diagnostics: List<BpmnDiagnostic>): String =
        buildString {
            append("BPMN validator infrastructure failure")
            diagnostics
                .firstOrNull()
                ?.message
                ?.takeIf { it.isNotBlank() }
                ?.let { append(": $it") }
            appendLine()
            appendLine("Non-repairable bpmn-lint diagnostic(s):")
            diagnostics.forEach { diagnostic -> appendLine("- ${formatDiagnostic(diagnostic)}") }
        }.trim()

    fun formatDiagnostic(diagnostic: BpmnDiagnostic): String = diagnostic.format()

    private fun BpmnDiagnostic.isValidatorInfrastructureFailure(): Boolean {
        if (source != BpmnDiagnosticSource.LINT || rule != "parse-error") return false
        return VALIDATOR_INFRASTRUCTURE_MESSAGE_HINTS.any { message.contains(it, ignoreCase = true) }
    }

    private fun inferRepairScope(
        diagnostic: BpmnDiagnostic,
        ownerRef: String?,
    ): BpmnRepairScope {
        if (diagnostic.elementId?.endsWith("_di") == true) return BpmnRepairScope.LAYOUT
        return when (diagnostic.source) {
            BpmnDiagnosticSource.RENDER -> {
                if (LAYOUT_HINTS.any { diagnostic.message.contains(it, ignoreCase = true) }) {
                    BpmnRepairScope.LAYOUT
                } else {
                    BpmnRepairScope.FULL_PROCESS
                }
            }

            BpmnDiagnosticSource.GRAPH -> {
                if (ownerRef != null || diagnostic.objectRef != null) {
                    BpmnRepairScope.PHASE
                } else {
                    BpmnRepairScope.COMPOSITION
                }
            }

            BpmnDiagnosticSource.XSD,
            BpmnDiagnosticSource.LINT,
            -> {
                when {
                    ownerRef != null -> BpmnRepairScope.PHASE
                    diagnostic.objectRef == "process" -> BpmnRepairScope.COMPOSITION
                    diagnostic.elementId != null -> BpmnRepairScope.COMPOSITION
                    else -> BpmnRepairScope.FULL_PROCESS
                }
            }
        }
    }

    companion object {
        private val LAYOUT_HINTS = listOf("waypoint", "bounds", "diagram", "layout")
        private val VALIDATOR_INFRASTRUCTURE_MESSAGE_HINTS =
            listOf(
                "unknown rule",
                "Config resolution not supported",
                "resolveRule",
                "resolver",
                "bpmnlint-bundle",
                "bpmn-lint execution error",
            )
    }
}
