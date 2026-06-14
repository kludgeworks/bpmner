/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

/**
 * Shadow-only future lint config shape matching the planned `bpmner.pkl` template.
 * Runtime loading still uses Pkl profiles through RuleProfileFactory in this stage.
 */
data class BpmnerLintConfig(
    val profile: String = "recommended",
    val severityOverrides: Map<String, String?> = emptyMap(),
)
