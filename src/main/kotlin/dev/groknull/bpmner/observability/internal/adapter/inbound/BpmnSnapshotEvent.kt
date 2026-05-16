package dev.groknull.bpmner.observability.internal.adapter.inbound

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.AgentProcess
import dev.groknull.bpmner.core.BpmnDiagnostic

class BpmnSnapshotEvent(
    process: AgentProcess,
    val stage: String,
    val attemptNumber: Int? = null,
    val xml: String,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
) : AbstractAgentProcessEvent(process) {
    // Adding structural counts to make it easier for the UI
    val graphIssues: Int = diagnostics.count { it.source == dev.groknull.bpmner.core.BpmnDiagnosticSource.GRAPH }
    val xsdIssues: Int = diagnostics.count { it.source == dev.groknull.bpmner.core.BpmnDiagnosticSource.XSD }
    val lintIssues: Int = diagnostics.count { it.source == dev.groknull.bpmner.core.BpmnDiagnosticSource.LINT }
}
