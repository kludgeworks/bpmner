package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnDefinition

internal interface BpmnLocalModelFixHandler {
    val handlerName: String

    fun buildPatch(
        definition: BpmnDefinition,
        elementId: String,
    ): List<BpmnPatchOperation>
}
