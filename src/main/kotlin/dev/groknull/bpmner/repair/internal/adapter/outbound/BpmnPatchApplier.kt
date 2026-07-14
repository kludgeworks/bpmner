/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.outbound

import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchApplicationPort
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPatch
import dev.groknull.bpmner.repair.internal.domain.PatchApplicationResult
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.stereotype.Component

@InfrastructureRing
@Component
internal open class BpmnPatchApplier : BpmnPatchApplicationPort {
    override fun apply(
        definition: BpmnDefinition,
        patch: BpmnRepairPatch,
    ): PatchApplicationResult {
        var current = definition
        var anyChange = false

        for (op in patch.operations) {
            when (val result = BpmnPatchOperationApplier.applyOperation(current, op)) {
                is OperationResult.Changed -> {
                    current = result.definition
                    anyChange = true
                }

                is OperationResult.Unchanged -> {
                    Unit
                }

                is OperationResult.Invalid -> {
                    return PatchApplicationResult.Failure(result.reason)
                }
            }
        }

        return if (anyChange) PatchApplicationResult.Success(current) else PatchApplicationResult.NoOp
    }
}
