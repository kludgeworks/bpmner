/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring

import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.domain.io.UserInput
import org.jmolecules.architecture.onion.simplified.ApplicationRing

@ApplicationRing
fun interface BpmnRequestDrafter {
    fun draftRequest(userInput: UserInput, context: OperationContext): BpmnRequestDraft
}
