package dev.groknull.bpmner.layout.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import dev.groknull.bpmner.core.AutoFixedBpmnXml
import dev.groknull.bpmner.core.BpmnAutoFixChange
import dev.groknull.bpmner.core.BpmnAutoFixError
import dev.groknull.bpmner.core.BpmnAutoFixSkip
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnRepairScope
import dev.groknull.bpmner.core.FinalValidatedBpmnXml
import dev.groknull.bpmner.core.LayoutedBpmnXml
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnLayoutService
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory

@PrimaryAdapter
@Agent(description = "Apply auto-layout and final validation to validated BPMN XML")
internal class BpmnLayoutAgent(
    private val layoutService: BpmnLayoutService,
    private val bpmnLintingPort: BpmnLintingPort,
    private val bpmnXsdValidationPort: BpmnXsdValidationPort,
) {
    private val logger = LoggerFactory.getLogger(BpmnLayoutAgent::class.java)

    @Action(description = "Apply bounded deterministic XML auto-fixes before layout")
    @Suppress("ReturnCount") // guard-clause early returns on unavailable lint/autoFix services
    fun autoFixBpmnXml(bpmn: ValidatedBpmnXml): AutoFixedBpmnXml {
        val lintIssues = bpmnLintingPort.lint(bpmn.xml, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
        if (lintIssues == null) {
            logger.warn("BPMN XML auto-fix skipped because bpmn-lint validation was unavailable")
            return AutoFixedBpmnXml(xml = bpmn.xml)
        }

        val autoFixResult = bpmnLintingPort.autoFix(bpmn.xml, lintIssues, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
        if (autoFixResult == null) {
            logger.warn("BPMN XML auto-fix was unavailable; keeping validated XML")
            return AutoFixedBpmnXml(xml = bpmn.xml)
        }

        if (autoFixResult.applied.isNotEmpty() ||
            autoFixResult.skipped.isNotEmpty() ||
            autoFixResult.errors.isNotEmpty()
        ) {
            logger.info(
                "BPMN XML auto-fix result: changed={}, applied={}, skipped={}, errors={}",
                autoFixResult.changed,
                autoFixResult.applied.joinToString("; ") { it.summary() },
                autoFixResult.skipped.joinToString("; ") { it.summary() },
                autoFixResult.errors.joinToString("; ") { it.summary() },
            )
        }

        if (autoFixResult.changed) {
            val xsdIssues = bpmnXsdValidationPort.validateDetailed(autoFixResult.xml)
            if (xsdIssues.isNotEmpty()) {
                logger.warn(
                    "BPMN XML auto-fix produced XSD-invalid XML; keeping validated XML. " +
                        "applied={}, skipped={}, errors={}, xsdErrors={}",
                    autoFixResult.applied.joinToString("; ") { it.summary() },
                    autoFixResult.skipped.joinToString("; ") { it.summary() },
                    autoFixResult.errors.joinToString("; ") { it.summary() },
                    xsdIssues.joinToString("; ") { it.summary() },
                )
                return AutoFixedBpmnXml(xml = bpmn.xml, autoFixResult = autoFixResult)
            }
        }

        return AutoFixedBpmnXml(
            xml = if (autoFixResult.changed) autoFixResult.xml else bpmn.xml,
            autoFixResult = autoFixResult,
        )
    }

    @Action(description = "Apply auto-layout to the auto-fixed BPMN XML")
    fun layoutBpmnXml(bpmn: AutoFixedBpmnXml): LayoutedBpmnXml {
        val layoutedXml = layoutService.layout(bpmn.xml)
        return LayoutedBpmnXml(xml = layoutedXml)
    }

    @Action(description = "Validate the final layouted BPMN XML without semantic repair")
    fun validateFinalBpmnXml(bpmn: LayoutedBpmnXml): FinalValidatedBpmnXml {
        val diagnostics = mutableListOf<BpmnDiagnostic>()
        diagnostics +=
            bpmnXsdValidationPort.validateDetailed(bpmn.xml).map { issue ->
                BpmnDiagnostic(
                    source = BpmnDiagnosticSource.XSD,
                    message = issue.message,
                    elementId = issue.elementId,
                    repairScope = BpmnRepairScope.FULL_PROCESS,
                )
            }

        if (diagnostics.none { it.source == BpmnDiagnosticSource.XSD }) {
            val lintIssues = bpmnLintingPort.lint(bpmn.xml, BpmnLintPhase.FINAL_POST_LAYOUT)
            if (lintIssues == null) {
                logger.warn("Final bpmn-lint validation was unavailable; continuing without lint feedback")
            } else {
                diagnostics +=
                    lintIssues.map { issue ->
                        val isLayoutDiagnostic = issue.rule.isLayoutSensitiveLintRule()
                        BpmnDiagnostic(
                            source = BpmnDiagnosticSource.LINT,
                            message = issue.message,
                            rule = issue.rule,
                            category = issue.category,
                            elementId = issue.id,
                            repairScope =
                                if (isLayoutDiagnostic) BpmnRepairScope.LAYOUT else BpmnRepairScope.FULL_PROCESS,
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

    private fun finalValidationMessage(diagnostics: List<BpmnDiagnostic>): String =
        buildString {
            append("Final BPMN validation failed after auto-layout")
            val layoutDiagnostics = diagnostics.filter { it.repairScope == BpmnRepairScope.LAYOUT }
            if (layoutDiagnostics.isNotEmpty()) {
                append("; layout diagnostics remain after auto-layout")
            }
            append(": ")
            append(
                diagnostics
                    .groupingBy { it.source }
                    .eachCount()
                    .entries
                    .joinToString(",") { "${it.key.name.lowercase()}=${it.value}" },
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

    private fun BpmnAutoFixChange.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

    private fun BpmnAutoFixSkip.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

    private fun BpmnAutoFixError.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

    private fun XsdValidationIssue.summary(): String = listOfNotNull(elementId, message).joinToString("|")
}

class BpmnFinalValidationException(
    message: String,
) : IllegalStateException(message)
