/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair

import com.embabel.agent.api.common.ActionContext
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
fun interface BpmnRepairer {
    fun validateInitial(
        ready: ReadyBpmnContext,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        contract: ValidatedProcessContract,
        context: ActionContext,
    ): ValidatedBpmnXml
}
