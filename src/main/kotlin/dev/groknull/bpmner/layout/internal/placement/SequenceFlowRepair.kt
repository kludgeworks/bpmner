/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import org.camunda.bpm.model.bpmn.instance.SequenceFlow

/**
 * Re-anchors every sequence flow touched by [CollaborationFramePlacement]'s whole-participant
 * pool-stacking translation: rigidly translates its waypoints and label by the same vector as its
 * endpoints. A sequence flow never spans participants (only message flows do), so a touched
 * flow's endpoints always share one participant vector — there is no unequal-vector case left to
 * re-route (AD-730-01, AD-730-06): lane placement no longer moves any member, so it contributes no
 * translation for this repair to handle.
 */
internal object SequenceFlowRepair {

    fun repair(translations: Map<String, Point>, ctx: PlacementContext) {
        ctx.model.getModelElementsByType(SequenceFlow::class.java)
            .filter { flow -> flow.source?.id in translations || flow.target?.id in translations }
            .sortedBy { it.id }
            .forEach { flow ->
                val vector = translations[flow.source?.id] ?: translations[flow.target?.id] ?: return@forEach
                ctx.edges[flow.id] = ctx.edges[flow.id]?.map { Point(it.x + vector.x, it.y + vector.y) } ?: return@forEach
                ctx.labels[flow.id]?.let { label ->
                    ctx.labels[flow.id] = label.copy(x = label.x + vector.x, y = label.y + vector.y)
                }
            }
    }
}
