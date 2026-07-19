/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import dev.groknull.bpmner.repair.internal.adapter.outbound.RepairFixtures
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPatch

/**
 * Published test fixture for the `repair` module.
 *
 * Delegates to [RepairFixtures] (in `repair.internal.adapter.outbound`) — a same-module
 * reach that is legitimate here. Cross-module callers import only this object from the
 * `repair` root, never reaching into the internal package directly.
 *
 * (S5 — ARCHITECTURE §5 S5, §1.5; cross-module test fixture published at module root)
 */
object RepairTestFixtures {
    /**
     * Renders the `bpmner/repair/patch_feedback` template with canonical inputs.
     * Delegates to [RepairFixtures.renderPatchFeedback].
     */
    @JvmStatic
    fun renderPatchFeedback(): String = RepairFixtures.renderPatchFeedback()

    /**
     * Renders the `bpmner/repair/full_feedback` template with canonical inputs.
     * Delegates to [RepairFixtures.renderFullFeedback].
     */
    @JvmStatic
    fun renderFullFeedback(): String = RepairFixtures.renderFullFeedback()

    /**
     * The runtime class of [BpmnRepairPatch], exposed for schema-shape inspection without
     * requiring callers to import the internal (and Kotlin-`internal`-visibility) type.
     */
    @JvmField
    @Suppress("UNCHECKED_CAST")
    val BPMN_REPAIR_PATCH_CLASS: Class<Any> = BpmnRepairPatch::class.java as Class<Any>
}
