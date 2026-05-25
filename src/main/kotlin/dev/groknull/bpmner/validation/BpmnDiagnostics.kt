/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import com.fasterxml.jackson.annotation.JsonClassDescription
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.api.RepairSafety

enum class BpmnDiagnosticSource {
    GRAPH,
    RENDER,
    XSD,
    LINT,
}

enum class BpmnRepairScope {
    LABEL,
    OUTLINE,
    PHASE,
    COMPOSITION,
    FULL_PROCESS,
}

/**
 * Severity classification for a [BpmnDiagnostic].
 *
 * Contract:
 *   - [ERROR]   — must be fixed; pipeline must not declare success while any remain.
 *   - [WARNING] — surfaces but advises; pipeline can succeed with warnings present.
 *   - [INFO]    — FYI only; never blocks, never repaired.
 *
 * Mirrors bpmnlint's native severity model and the broader BPMN tooling convention
 * (https://github.com/bpmn-io/bpmnlint). Default for unspecified lint rules is [WARNING] —
 * structural rules are explicitly raised to [ERROR] via the Pkl catalog.
 */
enum class BpmnDiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
    ;

    companion object {
        /**
         * Map a raw lint-output severity string (e.g. from bpmnlint or Pkl rule metadata) to
         * the typed enum. Unrecognised or null values default to [WARNING] — the conservative
         * choice for the documentation-grade pipeline.
         */
        fun fromLintCategory(raw: String?): BpmnDiagnosticSeverity = when (raw?.lowercase()) {
            "error" -> ERROR
            "warn", "warning" -> WARNING
            "info" -> INFO
            else -> WARNING
        }
    }
}

@JsonClassDescription(
    "Normalized BPMN validation or rendering diagnostic linked back to the typed definition where possible",
)
data class BpmnDiagnostic(
    val source: BpmnDiagnosticSource,
    val message: String,
    val severity: BpmnDiagnosticSeverity = BpmnDiagnosticSeverity.WARNING,
    val rule: String? = null,
    val elementId: String? = null,
    val objectRef: String? = null,
    val repairScope: BpmnRepairScope? = null,
    val ownerRef: String? = null,
    val kind: RepairKind? = null,
    val repairSafety: RepairSafety? = null,
    val fixHandler: String? = null,
) {
    /** True for [BpmnDiagnosticSeverity.ERROR] diagnostics that must be fixed before the pipeline can succeed. */
    val isBlocking: Boolean
        get() = severity == BpmnDiagnosticSeverity.ERROR
}

data class GlobalDiagnostics(
    val diagnostics: List<BpmnDiagnostic>,
) {
    fun countFor(source: BpmnDiagnosticSource): Int = diagnostics.count { it.source == source }
}

fun BpmnDiagnostic.format(): String = buildString {
    append("source=${source.name.lowercase()}")
    rule?.let { append(", rule=$it") }
    append(", severity=${severity.name.lowercase()}")
    elementId?.let { append(", elementId=$it") }
    objectRef?.let { append(", objectRef=$it") }
    repairScope?.let { append(", repairScope=${it.name.lowercase()}") }
    ownerRef?.let { append(", owner=$it") }
    kind?.let { append(", kind=${it.name.lowercase()}") }
    append(": $message")
}
