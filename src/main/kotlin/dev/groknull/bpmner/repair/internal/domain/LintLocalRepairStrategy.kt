package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.generation.BpmnXmlParser
import dev.groknull.bpmner.repair.BpmnLocalFixFailure
import dev.groknull.bpmner.repair.BpmnLocalFixSummary
import dev.groknull.bpmner.repair.BpmnLocalRepairOutcome
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnDiagnosticSource
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.BpmnLintRuleIds
import dev.groknull.bpmner.validation.BpmnLintingPort
import dev.groknull.bpmner.validation.BpmnXsdValidationPort
import dev.groknull.bpmner.validation.LintIssue
import dev.groknull.bpmner.validation.RepairKind
import org.jmolecules.ddd.annotation.Service
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Service
@Component
internal class LintLocalRepairStrategy(
    private val lintingPort: BpmnLintingPort,
    private val xsdValidationPort: BpmnXsdValidationPort,
    private val xmlParser: BpmnXmlParser,
    private val modelFixHandlerRegistry: BpmnLocalModelFixHandlerRegistry,
    private val patchApplier: BpmnPatchApplicationPort,
) : BpmnRepairStrategy {
    private val logger = LoggerFactory.getLogger(LintLocalRepairStrategy::class.java)

    override fun getOrder(): Int = 75

    override fun repair(context: BpmnRepairStrategyContext): BpmnRepairResult {
        val attempt = context.attempt
        val modelFixResult = tryLocalModelFix(attempt)
        if (modelFixResult !is BpmnRepairResult.NotApplicable) {
            return modelFixResult
        }
        val rendered = attempt.evaluation.rendered ?: return BpmnRepairResult.NotApplicable
        val rawIssues = attempt.evaluation.rawLintIssues ?: return BpmnRepairResult.NotApplicable
        val localXmlRules = collectLocalXmlRules(attempt.diagnostics)
        val issuesForFix = rawIssues.filter { it.bareRuleId() in localXmlRules }
        return when {
            localXmlRules.isEmpty() -> BpmnRepairResult.NotApplicable
            issuesForFix.isEmpty() -> BpmnRepairResult.NotApplicable
            else -> runAutoFixAndDecide(rendered.xml, issuesForFix, localXmlRules, attempt)
        }
    }

    private fun tryLocalModelFix(attempt: BpmnRepairAttempt): BpmnRepairResult {
        attempt.diagnostics.forEach { diagnostic ->
            val repaired = applyLocalModelFix(attempt, diagnostic)
            if (repaired != null) return repaired
        }
        return BpmnRepairResult.NotApplicable
    }

    private fun applyLocalModelFix(
        attempt: BpmnRepairAttempt,
        diagnostic: BpmnDiagnostic,
    ): BpmnRepairResult.Repaired? {
        if (diagnostic.kind != RepairKind.LOCAL_MODEL_FIX) return null
        val handlerName = diagnostic.fixHandler ?: return null
        val elementId = diagnostic.elementId ?: return null
        val handler = modelFixHandlerRegistry.lookup(handlerName) ?: return null
        val ops = handler.buildPatch(attempt.definition, elementId)
        if (ops.isEmpty()) return null
        val patch =
            BpmnRepairPatch(
                operations = ops,
                reason = "LOCAL_MODEL_FIX: $handlerName on $elementId",
            )
        return when (val applied = patchApplier.apply(attempt.definition, patch)) {
            is PatchApplicationResult.Success -> {
                logger.info("Local model fix applied: handler={}, elementId={}", handlerName, elementId)
                BpmnRepairResult.Repaired(
                    definition = applied.definition,
                    promptText = patch.reason ?: "Local model fix",
                    messages = attempt.messages,
                    localFixSummary = BpmnLocalFixSummary(modelApplied = 1, xmlApplied = 0, xmlErrors = 0),
                )
            }

            is PatchApplicationResult.Failure -> {
                logger.warn(
                    "Local model fix produced invalid patch; falling through. handler={}, elementId={}, reason={}",
                    handlerName,
                    elementId,
                    applied.reason,
                )
                null
            }

            PatchApplicationResult.NoOp -> {
                null
            }
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

    private fun collectLocalXmlRules(diagnostics: List<BpmnDiagnostic>): Set<String> =
        diagnostics
            .asSequence()
            .filter { it.source == BpmnDiagnosticSource.LINT && it.kind == RepairKind.LOCAL_XML_FIX }
            .mapNotNull { it.bareRuleId() }
            .toSet()

    private fun failOnDeclaredLocalSkipped(
        result: BpmnAutoFixResult,
        localXmlRules: Set<String>,
    ) {
        val declaredLocalSkipped =
            result.skipped.filter { skip ->
                BpmnLintRuleIds.bareRuleId(skip.rule) in localXmlRules
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
            localFixSummary =
                BpmnLocalFixSummary(
                    modelApplied = 0,
                    xmlApplied = result.applied.size,
                    xmlErrors = result.errors.size,
                ),
        )
    }

    private fun BpmnDiagnostic.bareRuleId(): String? = rule?.let(BpmnLintRuleIds::bareRuleId)

    private fun LintIssue.bareRuleId(): String = BpmnLintRuleIds.bareRuleId(rule)
}
