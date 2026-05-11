package dev.groknull.bpmner.validation

import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.LintIssue
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
interface BpmnLintingPort {
    fun lint(
        bpmnXml: String,
        phase: BpmnLintPhase,
    ): List<LintIssue>?

    fun autoFix(
        bpmnXml: String,
        issues: List<LintIssue>,
        phase: BpmnLintPhase,
    ): BpmnAutoFixResult?

    fun ruleDocs(ruleNames: Collection<String>): Map<String, String>
}
