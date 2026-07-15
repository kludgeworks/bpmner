/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.contract

import com.embabel.agent.api.common.OperationContext
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import org.jmolecules.architecture.onion.simplified.ApplicationRing

@ApplicationRing
fun interface ProcessContractExtractor {
    fun extract(ready: ReadyBpmnContext, context: OperationContext): ValidatedProcessContract
}
