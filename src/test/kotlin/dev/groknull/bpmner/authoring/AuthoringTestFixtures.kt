/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition

/**
 * Published test fixture for the `authoring` module's flat wire-format type.
 *
 * [FlatBpmnDefinition] lives in `authoring.internal.adapter.outbound` (the LLM adapter
 * boundary), and is legitimately accessed here because this fixture is in the `authoring`
 * module's test scope (same-module reach). Cross-module test callers import only this
 * object from the `authoring` root, never reaching into the internal package directly.
 *
 * (S5 — ARCHITECTURE §5 S5, §1.5; cross-module test fixture published at module root)
 */
object AuthoringTestFixtures {
    /**
     * The runtime class of [FlatBpmnDefinition], exposed for schema-shape inspection
     * without requiring callers to import the internal type.
     */
    @JvmField
    @Suppress("UNCHECKED_CAST")
    val FLAT_BPMN_DEFINITION_CLASS: Class<Any> = FlatBpmnDefinition::class.java as Class<Any>
}
