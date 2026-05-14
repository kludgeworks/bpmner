package dev.groknull.bpmner.repair.internal.adapter.outbound

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPatch
import dev.groknull.bpmner.repair.internal.domain.PatchApplicationResult
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.springframework.stereotype.Component

@SecondaryAdapter
@Component
internal open class BpmnPatchApplier {
    fun apply(
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
