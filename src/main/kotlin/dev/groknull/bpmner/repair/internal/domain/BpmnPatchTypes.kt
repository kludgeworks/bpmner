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

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode

internal data class BpmnRepairPatch(
    val operations: List<BpmnPatchOperation> = emptyList(),
    val reason: String? = null,
)

internal data class BpmnPatchOperation(
    val type: BpmnPatchOperationType,
    val nodeId: String? = null,
    val edgeId: String? = null,
    val name: String? = null,
    val label: String? = null,
    val node: BpmnNode? = null,
    val edge: BpmnEdge? = null,
)

internal enum class BpmnPatchOperationType {
    SET_NODE_NAME,
    SET_EDGE_LABEL,
    ADD_NODE,
    REMOVE_NODE,
    REPLACE_NODE,
    ADD_EDGE,
    REMOVE_EDGE,
    REPLACE_EDGE,
}

internal sealed class PatchApplicationResult {
    data class Success(
        val definition: BpmnDefinition,
    ) : PatchApplicationResult()

    data object NoOp : PatchApplicationResult()

    data class Failure(
        val reason: String,
    ) : PatchApplicationResult()
}
