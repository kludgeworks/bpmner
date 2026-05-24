/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.layout.LayoutedBpmnXml
import dev.groknull.bpmner.repair.AutoFixedBpmnXml
import dev.groknull.bpmner.validation.BpmnAutoFixChange
import dev.groknull.bpmner.validation.BpmnAutoFixError
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnAutoFixSkip
import dev.groknull.bpmner.validation.BpmnLintRuleIds
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.FinalValidatedBpmnXml
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import dev.groknull.bpmner.validation.XsdValidationIssue
import org.jmolecules.architecture.hexagonal.PrimaryAdapter
import org.slf4j.LoggerFactory

/**
 * Owns the post-repair pipeline: bounded pre-layout XML cleanup, auto-layout, and final XSD validation.
 *
 * The `autoFixBpmnXml` action is intentionally narrow. It is **not** the main semantic repair loop
 * — that is `DeterministicTopologyRepairStrategy` plus the LLM strategies, which all run before this agent ever
 * sees the XML. This stage exists only to apply XML-local cleanup whose `RepairKind` is
 * `LOCAL_XML_FIX`, and it falls back to the input XML on any failure.
 *
 * The `validateFinalBpmnXml` action runs XSD validation only against the layouted XML.
 * Semantic lint rules already ran pre-layout; re-running them post-layout would only repeat work,
 * and there are no layout-sensitive lint rules left in the catalog after auto-layout took over
 * coordinate generation.
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
        val rawIssues = bpmnLintingPort.lint(xml)
        if (rawIssues == null) {
            logger.warn("BPMN XML auto-fix skipped because bpmn-lint validation was unavailable")
            return null
        }
        val eligible = selectEligible(rawIssues)
        if (eligible.isEmpty()) return null
        val result = bpmnLintingPort.autoFix(xml, eligible)
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

    @AchievesGoal(
        description = "Apply auto-layout and final validation to validated BPMN XML",
        export = Export(name = "finalizeLayout", remote = true, startingInputTypes = [LayoutedBpmnXml::class]),
    )
    @Action(description = "XSD-validate the final layouted BPMN XML")
    fun validateFinalBpmnXml(bpmn: LayoutedBpmnXml): FinalValidatedBpmnXml {
        val xsdIssues = bpmnXsdValidationPort.validateDetailed(bpmn.xml)
        if (xsdIssues.isNotEmpty()) {
            throw BpmnLayoutCorruptionException(
                "Auto-layout produced structurally invalid BPMN: " +
                    xsdIssues.joinToString("; ") { it.summary() },
            )
        }
        logger.info("Final BPMN XSD validation passed after auto-layout")
        return FinalValidatedBpmnXml(definition = bpmn.definition, xml = bpmn.xml)
    }
}

private fun BpmnAutoFixChange.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

private fun BpmnAutoFixSkip.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

private fun BpmnAutoFixError.summary(): String = listOfNotNull(rule, elementId, message).joinToString("|")

private fun XsdValidationIssue.summary(): String = listOfNotNull(elementId, message).joinToString("|")

class BpmnLayoutCorruptionException(
    message: String,
) : IllegalStateException(message)
