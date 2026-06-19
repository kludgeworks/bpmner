/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain.handlers

import dev.groknull.bpmner.domain.BpmnDefinition

internal object TopologyIds {
    fun fresh(
        prefix: String,
        definition: BpmnDefinition,
    ): String {
        val taken = (definition.nodes.map { it.id } + definition.sequences.map { it.id }).toSet()
        var n = 1
        while ("${prefix}_$n" in taken) n++
        return "${prefix}_$n"
    }
}
