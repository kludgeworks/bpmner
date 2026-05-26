/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition

internal interface BpmnLocalModelFixHandler {
    val handlerName: String

    fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
        config: HandlerConfig = HandlerConfig.EMPTY,
    ): List<BpmnPatchOperation>
}
