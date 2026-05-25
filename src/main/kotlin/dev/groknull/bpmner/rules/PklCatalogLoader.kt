/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

import dev.groknull.bpmner.api.BpmnRule

fun interface PklCatalogLoader {
    fun loadRules(): List<BpmnRule>
}
