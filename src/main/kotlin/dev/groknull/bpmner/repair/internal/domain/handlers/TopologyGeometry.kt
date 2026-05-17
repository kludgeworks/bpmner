/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.core.BpmnDefinition

internal object TopologyGeometry {
    const val JOIN_GATEWAY_X_OFFSET: Double = 80.0
    const val GATEWAY_SIZE: Double = 50.0
    const val GATEWAY_HALF_SIZE: Double = 25.0

    fun freshId(
        prefix: String,
        definition: BpmnDefinition,
    ): String {
        val taken = (definition.nodes.map { it.id } + definition.sequences.map { it.id }).toSet()
        var n = 1
        while ("${prefix}_$n" in taken) n++
        return "${prefix}_$n"
    }
}
