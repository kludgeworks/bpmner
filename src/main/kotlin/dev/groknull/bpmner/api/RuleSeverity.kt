/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Severity classification for a rule diagnostic.
 *
 * - [ERROR] — must be fixed; pipeline must not declare success while any remain.
 * - [WARNING] — surfaces but advises; pipeline can succeed with warnings present.
 * - [INFO] — FYI only; never blocks, never repaired.
 */
enum class RuleSeverity {
    ERROR,
    WARNING,
    INFO,
    ;

    companion object {
        /**
         * Map a raw lint-output severity string (e.g. from bpmnlint or Pkl rule metadata)
         * to the typed enum. Unrecognised or null values default to [WARNING].
         */
        fun fromLintCategory(raw: String?): RuleSeverity =
            when (raw?.lowercase()) {
                "error" -> ERROR
                "warn", "warning" -> WARNING
                "info" -> INFO
                else -> WARNING
            }
    }
}
