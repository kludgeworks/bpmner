/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound.placement

import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.POSITION_EPSILON
import dev.groknull.bpmner.layout.internal.adapter.outbound.BpmnPlacementPass.Point
import org.camunda.bpm.model.bpmn.instance.EndEvent
import org.camunda.bpm.model.bpmn.instance.SequenceFlow
import org.camunda.bpm.model.bpmn.instance.SubProcess
import org.camunda.bpm.model.xml.instance.ModelElementInstance

/**
 * Pipeline entries 3 (Move) and 10 (Repair) — declared move: subprocess-terminating end straddle.
 *
 * **AD-557-14 admission record for the move:**
 * Phase-1 attempt: spike 2 confirmed layer constraints can guide ELK's end-event placement, but
 * ELK does not support the BPMN "straddle" convention (centre on container border) as a native
 * constraint. The human-approved corpus (`subprocess-nested` golden) demands this geometry.
 *
 * ## Move (entry 3)
 * Postcondition: each end event inside an expanded subprocess has its centre on the subprocess's
 * right border (half inside, half outside); the move is ledgered with owner "SubprocessEndStraddle".
 *
 * ## Repair (entry 10)
 * Postcondition: edges whose target is a straddled end are re-anchored to its new position;
 * edges whose source is the subprocess container exit from the straddled end's right edge.
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
        val straddledEnds = ctx.moves
            .filter { it.value.owner == "SubprocessEndStraddle" }
            .keys
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
