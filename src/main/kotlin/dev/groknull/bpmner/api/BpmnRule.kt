/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * Annotation-free contract for a BPMN rule. Implementations evaluate a
 * [BpmnDefinitionContext] (the pre-computed index over a `BpmnDefinition`) and return
 * the diagnostics they find.
 *
 * Implementations live in three tiers (see [#208](https://github.com/kludgeworks/bpmner/issues/208)):
 *  - **Tier 1 — compiled Kotlin rules** in `rules/internal/domain/compiled/` (#213).
 *  - **Tier 2 — Kotlin bean rules** declared in `rules/internal/domain/beans/` (#376).
 *  - **Tier 3 — plugin JARs** loaded via Spring Boot `loader.path` on the JVM distribution.
 *
 * Documented (not annotated) as the Spring Modulith extension point: the `api` package
 * stays framework-free per `ApiAnnotationFreeTest`, so this KDoc is the only "extension
 * point" marker.
 *
 * @property id Stable rule identifier carried back on every [RuleDiagnostic.ruleId] this
 *   rule emits. Must be non-blank and unique across the active rule registry. A single
 *   rule may emit multiple distinct [RuleDiagnostic.diagnosticCode]s — severity overrides
 *   and repair dispositions key on `diagnosticCode`, not `id`.
 */
interface BpmnRule {
    val id: String
    val metadata: RuleMetadata

    fun evaluate(ctx: BpmnDefinitionContext): List<RuleDiagnostic>
}
