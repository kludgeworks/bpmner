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

package dev.groknull.bpmner.validation.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnNodeNamingPolicy
import dev.groknull.bpmner.core.NodeType
import org.jmolecules.ddd.annotation.Service
import org.springframework.stereotype.Component

@Service
@Component
internal class BpmnDefinitionValidator {
    fun validate(definition: BpmnDefinition): List<String> {
        val errors = mutableListOf<String>()

        validateDuplicateIds(definition, errors)
        validateNames(definition, errors)
        validateEdges(definition, errors)
        validateRequiredEvents(definition, errors)

        return errors
    }

    private fun validateDuplicateIds(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodeIds = definition.nodes.map { it.id.trim() }
        val edgeIds = definition.sequences.map { it.id.trim() }

        nodeIds
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate node id: $it") }

        edgeIds
            .groupBy { it }
            .filter { (id, all) -> id.isNotBlank() && all.size > 1 }
            .keys
            .forEach { errors.add("duplicate edge id: $it") }
    }

    private fun validateNames(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val outgoingCounts = definition.sequences.groupingBy { it.sourceRef }.eachCount()

        definition.nodes.forEach { node ->
            val requiresName =
                BpmnNodeNamingPolicy.requiresName(
                    node = node,
                    outgoingCount = outgoingCounts[node.id] ?: 0,
                )
            if (requiresName && node.name.isNullOrBlank()) {
                errors.add(BpmnNodeNamingPolicy.missingNameMessage(node))
            }
        }
    }

    private fun validateEdges(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        val nodeIdSet = definition.nodes.map { it.id }.toSet()
        definition.sequences.forEach { edge ->
            if (edge.sourceRef !in nodeIdSet) {
                errors.add(
                    "edge ${edge.id.ifBlank { "<blank>" }} sourceRef '${edge.sourceRef}' does not match any node id",
                )
            }
            if (edge.targetRef !in nodeIdSet) {
                errors.add(
                    "edge ${edge.id.ifBlank { "<blank>" }} targetRef '${edge.targetRef}' does not match any node id",
                )
            }
            if (edge.sourceRef == edge.targetRef) {
                errors.add("edge ${edge.id.ifBlank { "<blank>" }} must not self-reference source and target")
            }
        }
    }

    private fun validateRequiredEvents(
        definition: BpmnDefinition,
        errors: MutableList<String>,
    ) {
        if (definition.nodes.none { it.type == NodeType.START_EVENT }) {
            errors.add("definition must contain at least one START_EVENT")
        }
        if (definition.nodes.none { it.type == NodeType.END_EVENT }) {
            errors.add("definition must contain at least one END_EVENT")
        }
    }
}
