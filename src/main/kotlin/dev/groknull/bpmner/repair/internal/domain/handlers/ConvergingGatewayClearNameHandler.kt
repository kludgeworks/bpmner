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
internal class ConvergingGatewayClearNameHandler : BpmnLocalModelFixHandler {
    override val handlerName: String = "clearConvergingGatewayName"

    override fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
    ): List<BpmnPatchOperation> {
        val node = definition.nodes.firstOrNull { it.id == elementId } ?: return emptyList()
        if (node.name.isNullOrBlank()) return emptyList()
        val incoming = definition.sequences.count { it.targetRef == elementId }
        val outgoing = definition.sequences.count { it.sourceRef == elementId }
        // Converging-only: two or more incoming flows and at most one outgoing flow.
        if (incoming <= 1 || outgoing > 1) return emptyList()
        return listOf(BpmnPatchOperation(type = BpmnPatchOperationType.SET_NODE_NAME, nodeId = elementId, name = null))
    }
}
