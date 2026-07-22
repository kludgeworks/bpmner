/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point

/** Shared translate-and-ledger discipline for post-ELK node relocations. */
internal object PlacementTranslations {

    /**
     * Shifts each id's [ctx.shapes] entry by its mapped vector and ledgers the resulting
     * cumulative ELK-baseline displacement under [owner]. Returns [translations] unchanged
     * for callers that need to repair flows incident to the translated ids.
     */
    fun translateAndLedger(translations: Map<String, Point>, owner: String, ctx: PlacementContext): Map<String, Point> {
        translations.forEach { (id, vector) ->
            val rect = ctx.shapes[id] ?: return@forEach
            ctx.shapes[id] = rect.copy(x = rect.x + vector.x, y = rect.y + vector.y)
            ledgerMove(id, owner, ctx)
        }
        return translations
    }

    /** Ledgers [id]'s cumulative displacement from its ELK-skeleton baseline to its current placed shape. */
    fun ledgerMove(id: String, owner: String, ctx: PlacementContext) {
        val elkNode = ctx.skeleton.nodeMap[id] ?: return
        val placed = ctx.shapes[id] ?: return
        val (x, y) = BpmnPlacementPass.absolutePosition(elkNode)
        ctx.moves[id] = MoveRecord(owner, placed.x - x, placed.y - y)
    }
}
