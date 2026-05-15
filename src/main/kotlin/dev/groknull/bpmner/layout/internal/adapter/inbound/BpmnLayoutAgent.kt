package dev.groknull.bpmner.layout.internal.adapter.inbound

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import dev.groknull.bpmner.core.AutoFixedBpmnXml
import dev.groknull.bpmner.core.BpmnAutoFixChange
import dev.groknull.bpmner.core.BpmnAutoFixError
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnAutoFixSkip
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnLintRuleIds
import dev.groknull.bpmner.core.BpmnRepairScope
import dev.groknull.bpmner.core.FinalValidatedBpmnXml
import dev.groknull.bpmner.core.LayoutedBpmnXml
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.core.RepairKind
import dev.groknull.bpmner.core.ValidatedBpmnXml
import dev.groknull.bpmner.core.XsdValidationIssue
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory

/**
 * Owns the post-repair pipeline: bounded pre-layout XML cleanup, auto-layout, and final validation.
 *
 * The `autoFixBpmnXml` action is intentionally narrow. It is **not** the main semantic repair loop
 * — that is `LintLocalRepairStrategy` plus the LLM strategies, which all run before this agent ever
 * sees the XML. This stage exists only to apply XML-local cleanup whose `RepairKind` is
 * `LOCAL_XML_FIX`, and it falls back to the input XML on any failure.
 */
@PrimaryAdapter
@Agent(description = "Apply auto-layout and final validation to validated BPMN XML")
internal class BpmnLayoutAgent(
    private val layoutService: BpmnLayoutPort,
    private val bpmnLintingPort: BpmnLintingPort,
    private val bpmnXsdValidationPort: BpmnXsdValidationPort,
) {
    private val logger = LoggerFactory.getLogger(BpmnLayoutAgent::class.java)

    /**
     * Bounded pre-layout XML cleanup.
     *
     * Invariants:
     *  - Only diagnostics whose stamped capability `kind` is `LOCAL_XML_FIX` are passed to the
     *    auto-fixer. Everything else is filtered out before any fix is attempted.
     *  - XSD-invalid auto-fix output is rejected; the agent returns the unchanged validated XML.
     *  - No LLM repair is ever invoked from this stage.
     */
    @Action(description = "Apply bounded deterministic XML auto-fixes before layout")
    fun autoFixBpmnXml(bpmn: ValidatedBpmnXml): AutoFixedBpmnXml {
        val result = tryAutoFix(bpmn.xml) ?: return AutoFixedBpmnXml(definition = bpmn.definition, xml = bpmn.xml)
        if (result.changed && !autoFixedXmlIsXsdValid(result)) {
            return AutoFixedBpmnXml(definition = bpmn.definition, xml = bpmn.xml, autoFixResult = result)
        }
        return AutoFixedBpmnXml(
            definition = bpmn.definition,
            xml = if (result.changed) result.xml else bpmn.xml,
            autoFixResult = result,
        )
    }

    private fun tryAutoFix(xml: String): BpmnAutoFixResult? {
        val rawIssues = bpmnLintingPort.lint(xml, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
        if (rawIssues == null) {
            logger.warn("BPMN XML auto-fix skipped because bpmn-lint validation was unavailable")
            return null
        }
        val eligible = selectEligible(rawIssues)
        if (eligible.isEmpty()) return null
        val result = bpmnLintingPort.autoFix(xml, eligible, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
        if (result == null) {
            logger.warn("BPMN XML auto-fix was unavailable; keeping validated XML")
            return null
        }
        logAutoFixOutcome(result)
        return result
    }

    private fun selectEligible(rawIssues: List<LintIssue>): List<LintIssue> {
        val capabilities = bpmnLintingPort.lintRuleCapabilities()
        val (eligible, filtered) =
            rawIssues.partition { issue ->
                val cap = capabilities[BpmnLintRuleIds.bareRuleId(issue.rule)]
                cap?.kind == RepairKind.LOCAL_XML_FIX
            }
        if (filtered.isNotEmpty()) {
            val byRule =
                filtered
                    .groupingBy { it.rule }
                    .eachCount()
                    .entries
                    .joinToString(", ") { "${it.key}=${it.value}" }
            logger.info(
                "BPMN XML auto-fix filter: total={}, eligible={}, filtered={} [{}]",
                rawIssues.size,
                eligible.size,
                filtered.size,
                byRule,
            )
        }
        return eligible
    }

    private fun logAutoFixOutcome(result: BpmnAutoFixResult) {
        if (result.applied.isEmpty() && result.skipped.isEmpty() && result.errors.isEmpty()) return
        logger.info(
            "BPMN XML auto-fix outcome: changed={}, applied={}, skipped={}, errors={}",
            result.changed,
            result.applied.joinToString("; ") { it.summary() },
            result.skipped.joinToString("; ") { it.summary() },
            result.errors.joinToString("; ") { it.summary() },
        )
    }

    private fun autoFixedXmlIsXsdValid(result: BpmnAutoFixResult): Boolean {
        val xsdIssues = bpmnXsdValidationPort.validateDetailed(result.xml)
        if (xsdIssues.isEmpty()) return true
        logger.warn(
            "BPMN XML auto-fix rejected: XSD-invalid output, keeping validated XML. " +
                "applied={}, errors={}, xsdErrors={}",
            result.applied.joinToString("; ") { it.summary() },
            result.errors.joinToString("; ") { it.summary() },
            xsdIssues.joinToString("; ") { it.summary() },
        )
        return false
    }

    @Action(description = "Apply auto-layout to the auto-fixed BPMN XML")
    fun layoutBpmnXml(bpmn: AutoFixedBpmnXml): LayoutedBpmnXml {
        val layoutedXml = layoutService.layout(bpmn.xml)
        return LayoutedBpmnXml(definition = bpmn.definition, xml = layoutedXml)
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
                val capabilities = bpmnLintingPort.lintRuleCapabilities()
                diagnostics +=
                    lintIssues.map { issue ->
                        val isLayoutDiagnostic =
                            capabilities[BpmnLintRuleIds.bareRuleId(issue.rule)]?.layoutSensitive == true
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
        return FinalValidatedBpmnXml(definition = bpmn.definition, xml = bpmn.xml)
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
}

private fun BpmnAutoFixChange.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

private fun BpmnAutoFixSkip.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

private fun BpmnAutoFixError.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

private fun XsdValidationIssue.summary(): String = listOfNotNull(elementId, message).joinToString("|")

class BpmnFinalValidationException(
    message: String,
) : IllegalStateException(message)
