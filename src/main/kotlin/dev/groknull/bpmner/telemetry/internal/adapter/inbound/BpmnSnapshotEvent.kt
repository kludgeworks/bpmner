/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.telemetry.internal.adapter.inbound

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource

class BpmnSnapshotEvent(
    process: AgentProcess,
    val stage: String,
    val attemptNumber: Int? = null,
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
) : AbstractAgentProcessEvent(process) {
    val graphIssues: Int = diagnostics.count { it.source == BpmnDiagnosticSource.GRAPH }
    val xsdIssues: Int = diagnostics.count { it.source == BpmnDiagnosticSource.XSD }
    val lintIssues: Int = diagnostics.count { it.source == BpmnDiagnosticSource.LINT }
}
