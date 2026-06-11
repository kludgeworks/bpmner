/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.generation

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import dev.groknull.bpmner.core.BpmnRequestDraft
import org.jmolecules.architecture.hexagonal.PrimaryPort

@PrimaryPort
fun interface BpmnRequestDrafter {
    fun draftRequest(userInput: UserInput, context: OperationContext): BpmnRequestDraft
}
