/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

/**
 * Per-rule configuration handed to a [BpmnLocalModelFixHandler] when the dispatcher invokes it.
 *
 * Sourced from the rule catalog (`RuleCatalogService.getRule(...)` today; `RuleRegistry.ruleById(...).metadata`
 * after #217's 2D lands). Covered by:
 *  - [replacementMap] — repair-level replacement table (e.g. abbreviation expansions for `expandAbbreviations`).
 *
 * Handlers that don't need config accept the default [EMPTY] and ignore it.
 */
internal data class HandlerConfig(
    val replacementMap: Map<String, String>? = null,
) {
    companion object {
        val EMPTY: HandlerConfig = HandlerConfig()
    }
}
