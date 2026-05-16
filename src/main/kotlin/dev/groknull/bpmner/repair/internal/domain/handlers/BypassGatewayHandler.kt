/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandler
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperation
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchOperationType
import org.springframework.stereotype.Component

@Component
internal class BypassGatewayHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "bypassGateway"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
    ): List<BpmnPatchOperation> {
        val incomingEdge = definition.sequences.singleOrNull { it.targetRef == elementId } ?: return emptyList()
        val outgoingEdge = definition.sequences.singleOrNull { it.sourceRef == elementId } ?: return emptyList()
        val updatedIncoming =
            incomingEdge.copy(
                targetRef = outgoingEdge.targetRef,
                waypoints = listOf(incomingEdge.waypoints.first(), outgoingEdge.waypoints.last()),
            )
        return listOf(
            BpmnPatchOperation(
                type = BpmnPatchOperationType.REPLACE_EDGE,
                edgeId = incomingEdge.id,
                edge = updatedIncoming,
            ),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_EDGE, edgeId = outgoingEdge.id),
            BpmnPatchOperation(type = BpmnPatchOperationType.REMOVE_NODE, nodeId = elementId),
        )
    }
}
