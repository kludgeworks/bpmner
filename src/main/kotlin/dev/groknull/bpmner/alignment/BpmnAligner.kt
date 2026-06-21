/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.conformance.FinalValidatedBpmnXml
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
fun interface BpmnAligner {
    fun align(
        ready: ReadyBpmnContext,
        contract: ValidatedProcessContract,
        bpmn: FinalValidatedBpmnXml,
        context: OperationContext,
    ): BpmnAlignmentReport
}
