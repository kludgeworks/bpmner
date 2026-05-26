/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

/**
 * Per-rule configuration handed to a [BpmnLocalModelFixHandler] when the dispatcher invokes it.
 *
 * Sourced from the rule catalog (`RuleCatalogService.getRule(...)` today; `RuleRegistry.ruleById(...).metadata`
 * after #217's 2D lands). Two fields cover every handler that needs config so far:
 *  - [staticConfig] — opaque rule-level config (e.g. `discouragedWords` for `stripTypeWords`).
 *  - [replacementMap] — repair-level replacement table (e.g. abbreviation expansions for `expandAbbreviations`).
 *
 * Handlers that don't need config accept the default [EMPTY] and ignore it.
 */
internal data class HandlerConfig(
    val staticConfig: Map<String, Any>? = null,
    val replacementMap: Map<String, String>? = null,
) {
    companion object {
        val EMPTY: HandlerConfig = HandlerConfig()
    }
}
