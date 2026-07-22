/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.placement

import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.POSITION_EPSILON
import dev.groknull.bpmner.layout.internal.BpmnPlacementPass.Point
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.xml.instance.ModelElementInstance

/**
 * Declared move and repair: subprocess-terminating end straddle.
 *
 * Move places each end event inside an expanded subprocess so that its centre is on the subprocess's
 * right border (half inside, half outside).
 *
 * Repair re-anchors edges whose target is a straddled end to its new position, and exits
 * edges whose source is the subprocess container from the straddled end's right edge.
 */
internal object SubprocessEndStraddle {

    val Move: PlacementProcessor = PlacementProcessor { ctx ->
        ctx.model.getModelElementsByType(SubProcess::class.java).forEach { sub ->
            val subRect = ctx.shapes[sub.id] ?: return@forEach
            val rightBorder = subRect.x + subRect.w
            val subCentreY = subRect.y + subRect.h / 2.0
            sub.flowElements.filterIsInstance<EndEvent>()
                .forEach { end ->
                    val r = ctx.shapes[end.id] ?: return@forEach
                    val newX = rightBorder - r.w / 2.0
                    val newY = subCentreY - r.h / 2.0
                    val dx = newX - r.x
                    val dy = newY - r.y
                    ctx.shapes[end.id] = r.copy(x = newX, y = newY)
                    if (kotlin.math.abs(dx) > POSITION_EPSILON || kotlin.math.abs(dy) > POSITION_EPSILON) {
                        ctx.moves[end.id] = MoveRecord("SubprocessEndStraddle", dx, dy)
                    }
                }
        }
    }

    val Repair: PlacementProcessor = PlacementProcessor { ctx ->
        val ledgeredEnds = ctx.moves
            .filter { it.value.owner == "SubprocessEndStraddle" }
            .keys
        // Spine centring may subsequently record the same end node. Its final geometry still
        // identifies the straddle, so retain the edge-repair invariant when that happens.
        val geometricEnds = ctx.model.getModelElementsByType(SubProcess::class.java)
            .flatMap { sub ->
                val rightBorder = ctx.shapes[sub.id]?.let { it.x + it.w } ?: return@flatMap emptyList()
                sub.flowElements.filterIsInstance<EndEvent>()
                    .filter { end ->
                        ctx.shapes[end.id]?.let { shape ->
                            kotlin.math.abs(shape.x + shape.w / 2.0 - rightBorder) <= POSITION_EPSILON
                        } == true
                    }
                    .map { it.id }
            }
            .toSet()
        val straddledEnds = ledgeredEnds + geometricEnds
        if (straddledEnds.isEmpty()) return@PlacementProcessor

        ctx.model.getModelElementsByType(SequenceFlow::class.java).forEach { sf ->
            val srcId = sf.source?.id ?: return@forEach
            val tgtId = sf.target?.id ?: return@forEach
            // Case: exit edge whose SOURCE is the subprocess container.
            val srcElement = ctx.model.getModelElementById<ModelElementInstance>(srcId)
            if (srcElement is SubProcess) {
                val endId = srcElement.flowElements
                    .filterIsInstance<EndEvent>()
                    .firstOrNull { it.id in straddledEnds }?.id ?: return@forEach
                val endRect = ctx.shapes[endId] ?: return@forEach
                val tgtRect = ctx.shapes[tgtId] ?: return@forEach
                ctx.edges[sf.id] = listOf(
                    Point(endRect.x + endRect.w, endRect.y + endRect.h / 2.0),
                    Point(tgtRect.x, tgtRect.y + tgtRect.h / 2.0),
                )
                return@forEach
            }
            // Case: intra-subprocess edge whose TARGET is the straddled end event.
            if (tgtId in straddledEnds) {
                val endRect = ctx.shapes[tgtId] ?: return@forEach
                val wps = ctx.edges[sf.id] ?: return@forEach
                if (wps.size >= 2) {
                    val entryX = endRect.x
                    val entryCy = endRect.y + endRect.h / 2.0
                    val secondLast = wps[wps.size - 2]
                    ctx.edges[sf.id] = wps.dropLast(1) +
                        Point(secondLast.x, entryCy) +
                        Point(entryX, entryCy)
                }
            }
        }
    }
}
