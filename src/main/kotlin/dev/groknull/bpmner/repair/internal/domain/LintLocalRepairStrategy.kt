package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.BpmnLocalFixFailure
import dev.groknull.bpmner.core.BpmnLocalRepairOutcome
import dev.groknull.bpmner.core.BpmnRepairAttempt
import dev.groknull.bpmner.core.BpmnRepairRoute
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.generation.BpmnXmlParser
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Service
@Component
internal class LintLocalRepairStrategy(
    private val lintingPort: BpmnLintingPort,
    private val xsdValidationPort: BpmnXsdValidationPort,
    private val xmlParser: BpmnXmlParser,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(LintLocalRepairStrategy::class.java)

    override fun getOrder(): Int = 75

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val attempt = context.attempt
        val rendered = attempt.evaluation.rendered ?: return BpmnRepairResult.NotApplicable
        val rawIssues = attempt.evaluation.rawLintIssues ?: return BpmnRepairResult.NotApplicable
        warnIfLocalModelDiagnosticsPresent(attempt)
        val localXmlRules = collectLocalXmlRules(attempt.diagnostics)
        val issuesForFix = rawIssues.filter { it.bareRuleId() in localXmlRules }
        return when {
            localXmlRules.isEmpty() -> BpmnRepairResult.NotApplicable
            issuesForFix.isEmpty() -> BpmnRepairResult.NotApplicable
            else -> runAutoFixAndDecide(rendered.xml, issuesForFix, localXmlRules, attempt)
        }
    }

    private fun runAutoFixAndDecide(
        xml: String,
        issues: List<LintIssue>,
        localXmlRules: Set<String>,
        attempt: BpmnRepairAttempt,
    ): BpmnRepairResult {
        val result =
            lintingPort.autoFix(xml, issues, BpmnLintPhase.SEMANTIC_PRE_LAYOUT)
                ?: return BpmnRepairResult.NotApplicable
        failOnDeclaredLocalSkipped(result, localXmlRules)
        if (result.errors.isNotEmpty()) {
            logger.warn(
                "Local auto-fix errors; recording failures for LLM fallback context: {}",
                result.errors.joinToString { "${it.rule}@${it.elementId ?: "-"}:${it.message}" },
            )
            val failures =
                result.errors.map { err ->
                    BpmnLocalFixFailure(rule = err.rule, elementId = err.elementId, reason = err.message)
                }
            return BpmnRepairResult.LocalAttemptedNoChange(BpmnLocalRepairOutcome(failures))
        }
        return when {
            !isAutoFixUsable(result) -> BpmnRepairResult.NotApplicable
            !autoFixedXmlIsXsdValid(result) -> BpmnRepairResult.NotApplicable
            else -> toRepaired(result, attempt)
        }
    }

    private fun warnIfLocalModelDiagnosticsPresent(attempt: BpmnRepairAttempt) {
        if (attempt.diagnostics.any { it.repairRoute == BpmnRepairRoute.LOCAL_MODEL }) {
            logger.info(
                "LOCAL_MODEL diagnostics present but not handled in this phase (see #30); falling through",
            )
        }
    }

    private fun collectLocalXmlRules(diagnostics: List<BpmnDiagnostic>): Set<String> =
        diagnostics
            .asSequence()
            .filter { it.source == BpmnDiagnosticSource.LINT && it.repairRoute == BpmnRepairRoute.LOCAL_XML }
            .mapNotNull { it.bareRuleId() }
            .toSet()

    private fun failOnDeclaredLocalSkipped(
        result: BpmnAutoFixResult,
        localXmlRules: Set<String>,
    ) {
        val declaredLocalSkipped =
            result.skipped.filter { skip ->
                bareRuleId(skip.rule) in localXmlRules
            }
        check(declaredLocalSkipped.isEmpty()) {
            "Local lint auto-fix skipped declared-local rule(s) " +
                declaredLocalSkipped.joinToString { "${it.rule}@${it.elementId ?: "-"}" } +
                "; startup validation should have caught this"
        }
    }

    private fun isAutoFixUsable(result: BpmnAutoFixResult): Boolean = result.changed && result.applied.isNotEmpty()

    private fun autoFixedXmlIsXsdValid(result: BpmnAutoFixResult): Boolean {
        val xsdIssues = xsdValidationPort.validateDetailed(result.xml)
        if (xsdIssues.isNotEmpty()) {
            logger.warn(
                "Local auto-fix produced XSD-invalid XML; falling through. xsdErrors={}",
                xsdIssues.joinToString { it.message },
            )
            return false
        }
        return true
    }

    private fun toRepaired(
        result: BpmnAutoFixResult,
        attempt: BpmnRepairAttempt,
    ): BpmnRepairResult.Repaired {
        val newDefinition = xmlParser.parse(result.xml)
        logger.info(
            "Local lint auto-fix applied {} fix(es): {}",
            result.applied.size,
            result.applied.joinToString { "${it.rule}@${it.elementId ?: "-"}" },
        )
        return BpmnRepairResult.Repaired(
            definition = newDefinition,
            promptText = "Local lint auto-fix (" + result.applied.joinToString { it.rule } + ")",
            messages = attempt.messages,
        )
    }

    private fun BpmnDiagnostic.bareRuleId(): String? = rule?.let { bareRuleId(it) }

    private fun LintIssue.bareRuleId(): String = bareRuleId(rule)

    private fun bareRuleId(rule: String): String = rule.replace(RULE_PREFIX_REGEX, "")

    companion object {
        private val RULE_PREFIX_REGEX = "^(bpmner|bpmnlint-plugin-bpmner)/".toRegex()
    }
}
