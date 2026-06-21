/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

import dev.groknull.bpmner.bpmn.BpmnRule
import dev.groknull.bpmner.bpmn.RuleDiagnostic
import dev.groknull.bpmner.bpmn.RuleMetadata
import dev.groknull.bpmner.bpmn.RuleSeverity

internal fun RuleMetadata.diagnostic(elementId: String?, messageSuffix: String? = null): RuleDiagnostic {
    val template = errorMessages["default"] ?: errorMessages.values.first()
    return RuleDiagnostic(
        diagnosticCode = diagnosticCode(),
        ruleId = id,
        severity = severity,
        message = listOfNotNull(template, messageSuffix).joinToString(": "),
        elementId = elementId,
    )
}

/**
 * Emit a single rule-scoped diagnostic indicating that a rule's Pkl configuration is
 * malformed — used by primitives that compile user-supplied regexes or other config values
 * at evaluation time. The diagnostic is `RuleSeverity.ERROR` regardless of the rule's
 * declared severity, since a misconfigured rule cannot be skipped.
 */
internal fun RuleMetadata.configError(detail: String): RuleDiagnostic = RuleDiagnostic(
    diagnosticCode = "rule-config-error",
    ruleId = id,
    severity = RuleSeverity.ERROR,
    message = "Rule '$id' has invalid configuration: $detail",
    elementId = null,
)

internal fun RuleMetadata.diagnosticCode(): String = errorMessages.keys.firstOrNull { it != "default" } ?: id

internal fun RuleMetadata.targetedElements(model: PrimitiveModelContext): List<PrimitiveElement> = model.elements
    .filter { element ->
        targetElements.any { target -> BpmnTypeMatcher.matches(element.typeName, target) }
    }

internal interface PrimitiveRule : BpmnRule
