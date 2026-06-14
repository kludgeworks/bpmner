/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

/**
 * Shadow-only lint config shape matching the packaged `bpmner.pkl` template.
 * Runtime loading uses Pkl profiles through RuleProfileFactory; this type is not a startup config source.
 */
data class BpmnerLintConfig(
    val profile: String = "recommended",
    val severityOverrides: Map<String, String?> = emptyMap(),
)
