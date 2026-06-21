/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
interface BpmnProcessGenerator {
    fun createOutline(ready: ReadyBpmnContext, contract: ValidatedProcessContract, context: OperationContext): ValidatedOutline

    fun composeGraph(outline: ValidatedOutline): LaidOutProcessGraph

    fun render(ready: ReadyBpmnContext, graph: LaidOutProcessGraph): RenderedBpmn
}
