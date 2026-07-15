/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

import dev.groknull.bpmner.bpmn.BpmnDefinition
import org.jmolecules.architecture.onion.simplified.ApplicationRing

@ApplicationRing
interface BpmnLintingPort {
    fun lint(definition: BpmnDefinition): List<LintIssue>?

    fun autoFix(
        bpmnXml: String,
        issues: List<LintIssue>,
    ): BpmnAutoFixResult?

    fun ruleDocs(ruleNames: Collection<String>): Map<String, String>

    fun lintRuleCapabilities(): Map<String, BpmnLintRuleCapability>
}
