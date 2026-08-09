/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.authoring.internal.adapter.outbound

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Budget
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Verbosity
import dev.groknull.bpmner.authoring.BpmnAgentInvoker
import dev.groknull.bpmner.authoring.BpmnResult
import dev.groknull.bpmner.authoring.BpmnRunAbortedEvent
import dev.groknull.bpmner.authoring.internal.BpmnAuthoringBudgetConfig
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.llm.LlmInteractionLoggingConfig
import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.concurrent.CompletionException

@InfrastructureRing
@Component
internal class AgentPlatformBpmnAgentInvoker(
    private val agentPlatform: AgentPlatform,
    private val config: BpmnAuthoringBudgetConfig,
    private val logging: LlmInteractionLoggingConfig,
    /** Framework port — keeps `authoring` free of importing `pipeline.internal`. */
    private val outputChannel: OutputChannel,
    private val eventPublisher: ApplicationEventPublisher,
) : BpmnAgentInvoker {
    private val logger = LoggerFactory.getLogger(AgentPlatformBpmnAgentInvoker::class.java)
    override fun generate(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): BpmnResult {
        val agent =
            agentPlatform.agents().find { it.name == GENERATION_AGENT_NAME }
                ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        val process =
            agentPlatform.createAgentProcessFrom(
                agent,
                syncGenerationProcessOptions(),
                request,
                assessment,
            )
        process.run()
        // `fromProcessStatus()` returns the goal output on COMPLETED and throws the framework's
        // typed status exceptions (`ProcessExecutionStuckException` when the planner has no
        // applicable action, `ProcessExecutionTerminatedException` on budget exhaustion).
        // Using `process.resultOfType()` would crash silently on non-COMPLETED states.
        //
        // `AgentPlatformTypedOps.transform()` is not used here because that path uses
        // `process.resultOfType()` and loses the typed exception surface above.
        val execution = AgentProcessExecution.fromProcessStatus(request, process)
        return BpmnResult::class.java.cast(execution.output)
    }

    override fun startAsync(
        request: BpmnRequest,
        assessment: ProcessInputAssessment,
    ): String {
        val agent =
            agentPlatform.agents().find { it.name == GENERATION_AGENT_NAME }
                ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        val process =
            agentPlatform.createAgentProcessFrom(
                agent,
                asyncGenerationProcessOptions(),
                request,
                assessment,
            )
        startReportingAbort(process)
        return process.id
    }

    /**
     * Web-path overload: seeds only the [BpmnRequest]; the agent's `assessReadiness` action
     * (the `@State` machine) runs inside the process. Clarification surfaces as an in-process
     * `WaitFor` form over SSE — never a pre-computed 422 outcome.
     */
    override fun startAsync(request: BpmnRequest): String {
        val agent =
            agentPlatform.agents().find { it.name == GENERATION_AGENT_NAME }
                ?: error("Agent platform has no agent named '$GENERATION_AGENT_NAME'")
        val process =
            agentPlatform.createAgentProcessFrom(
                agent,
                asyncGenerationProcessOptions(),
                request,
            )
        startReportingAbort(process)
        return process.id
    }

    /**
     * Starts [process] in the background and publishes [BpmnRunAbortedEvent] if it ends by
     * throwing.
     *
     * An exception raised by an action propagates out of the process's run loop before the
     * platform can set a terminal status, so no lifecycle event is emitted and the failure would
     * otherwise be swallowed by this future — leaving a subscriber waiting on a run that has
     * already died.
     */
    private fun startReportingAbort(process: AgentProcess) {
        agentPlatform.start(process).whenComplete { _, error ->
            if (error == null) return@whenComplete
            val cause = (error as? CompletionException)?.cause ?: error
            logger.error("Generation process {} aborted with an unhandled exception", process.id, cause)
            eventPublisher.publishEvent(
                BpmnRunAbortedEvent(
                    processId = process.id,
                    detail = cause.message ?: cause::class.simpleName ?: "Unknown error",
                ),
            )
        }
    }

    // Short CLI run, never polled for status → ephemeral=true. Listeners aren't passed via
    // ProcessOptions: every AgenticEventListener bean already auto-registers globally, so this
    // would fire each event twice. outputChannel is bound here — the only supported registration
    // point for the run-update channel.
    private fun syncGenerationProcessOptions(): ProcessOptions = ProcessOptions(
        budget = Budget(actions = config.generation),
        verbosity = llmVerbosity(),
        ephemeral = true,
        outputChannel = outputChannel,
    )

    // Process must be persisted (ephemeral=false): callers poll for status after the id returns.
    private fun asyncGenerationProcessOptions(): ProcessOptions = ProcessOptions(
        budget = Budget(actions = config.generation),
        verbosity = llmVerbosity(),
        ephemeral = false,
        outputChannel = outputChannel,
    )

    private fun llmVerbosity(): Verbosity = Verbosity(
        showPrompts = logging.llmInteractions,
        showLlmResponses = logging.llmInteractions,
    )

    companion object {
        private const val GENERATION_AGENT_NAME = "BpmnGenerationAgent"
    }
}
