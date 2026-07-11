/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import com.embabel.agent.api.common.ActionContext
import dev.groknull.bpmner.alignment.BpmnAligner
import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.authoring.BpmnRequestDrafter
import dev.groknull.bpmner.authoring.BpmnRequestResolutionPort
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnEdge
import dev.groknull.bpmner.bpmn.BpmnEndEvent
import dev.groknull.bpmner.bpmn.BpmnStartEvent
import dev.groknull.bpmner.conformance.BpmnDiagnostic
import dev.groknull.bpmner.conformance.BpmnDiagnosticSeverity
import dev.groknull.bpmner.conformance.BpmnDiagnosticSource
import dev.groknull.bpmner.conformance.BpmnXsdValidationPort
import dev.groknull.bpmner.conformance.ValidatedBpmnXml
import dev.groknull.bpmner.contract.ProcessContractExtractor
import dev.groknull.bpmner.layout.BpmnLayoutPort
import dev.groknull.bpmner.readiness.BpmnReadinessInvoker
import dev.groknull.bpmner.repair.BpmnRepairer
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.context.ApplicationEventPublisher
import kotlin.test.assertIs

class BpmnGenerationAgentTest {
    @Test
    fun `blocking topology diagnostics stop before layout`() {
        val layoutPort = mock(BpmnLayoutPort::class.java)
        val validated =
            ValidatedBpmnXml(
                definition = minimalDefinition(),
                xml = "<definitions/>",
                diagnostics = listOf(
                    BpmnDiagnostic(
                        source = BpmnDiagnosticSource.LINT,
                        severity = BpmnDiagnosticSeverity.ERROR,
                        rule = "gtw-no-implicit-split",
                        message = "Non-gateway flow node has multiple outgoing flows",
                    ),
                ),
            )
        val agent = BpmnGenerationAgent(
            requestDrafter = mock(BpmnRequestDrafter::class.java),
            requestResolver = mock(BpmnRequestResolutionPort::class.java),
            readinessInvoker = mock(BpmnReadinessInvoker::class.java),
            contractExtractor = mock(ProcessContractExtractor::class.java),
            processGenerator = mock(BpmnProcessGenerator::class.java),
            repairer = BpmnRepairer { _, _, _, _, _ -> validated },
            layoutPort = layoutPort,
            xsdValidationPort = mock(BpmnXsdValidationPort::class.java),
            aligner = mock(BpmnAligner::class.java),
            eventPublisher = mock(ApplicationEventPublisher::class.java),
        )

        val stage = agent.validate(
            ready = mock(),
            g = mock(),
            r = mock(),
            c = mock(),
            ctx = mock(ActionContext::class.java),
        )

        assertIs<ValidationFailed>(stage)
        verifyNoInteractions(layoutPort)
    }

    private fun minimalDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Handle request",
        nodes = listOf(
            BpmnStartEvent("StartEvent_1", "Request received"),
            BpmnEndEvent("EndEvent_1", "Request completed"),
        ),
        sequences = listOf(BpmnEdge("Flow_1", "StartEvent_1", "EndEvent_1")),
    )
}
