/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.conformance

object BpmnLintRuleIds {
    private val PREFIX_REGEX = "^(bpmner|bpmnlint-plugin-bpmner)/".toRegex()

    fun bareRuleId(rule: String): String = rule.replace(PREFIX_REGEX, "")
}
