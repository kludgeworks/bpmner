/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import dev.groknull.bpmner.validation.BpmnAutoFixResult
import dev.groknull.bpmner.validation.BpmnLintPhase
import dev.groknull.bpmner.validation.BpmnLintRuleCapability
import dev.groknull.bpmner.validation.LintIssue
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
