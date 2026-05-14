package dev.groknull.bpmner.validation


import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
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

    fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability>
}
