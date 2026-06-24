/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.authoring.BpmnDefaultFlowPort
import dev.groknull.bpmner.authoring.BpmnProcessGenerator
import dev.groknull.bpmner.bpmn.BpmnDefinition
import dev.groknull.bpmner.bpmn.BpmnRequest
import dev.groknull.bpmner.bpmn.LaidOutProcessGraph
import dev.groknull.bpmner.bpmn.RenderedBpmn
import dev.groknull.bpmner.bpmn.withUpdatedDefinition
import dev.groknull.bpmner.conformance.BpmnEvaluation
import dev.groknull.bpmner.conformance.BpmnFingerprintService
import dev.groknull.bpmner.conformance.GlobalDiagnostics
import dev.groknull.bpmner.contract.ProcessContract
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.readiness.ReadyBpmnContext
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.repair.BpmnAttemptRecord
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
internal class BpmnRepairAdvancer(
    private val defaultFlowAssigner: BpmnDefaultFlowPort,
    private val contractAwareValidator: BpmnContractAwareValidator,
    private val attemptRecordFactory: BpmnAttemptRecordFactory,
    private val promptFactory: BpmnRepairPromptPort,
    private val fingerprints: BpmnFingerprintService,
    private val processGenerator: BpmnProcessGenerator,
) {
    private val logger = LoggerFactory.getLogger(BpmnRepairAdvancer::class.java)

    /**
     * Initial validation — normalise the incoming definition (default-flow re-stamp), seed
     * the LLM message chain, and produce the first [BpmnRepairEvaluation] on the blackboard.
     */
    fun initialEvaluation(
        ready: ReadyBpmnContext,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        validatedContract: ValidatedProcessContract,
    ): BpmnRepairEvaluation {
        val request = ready.request
        val contract = validatedContract.contract

        // Pre-flight: surface unrecognized parser fallbacks (`BpmnUnrecognizedNode`,
        // `BpmnUnrecognizedEventDefinition`) as typed UNFIXABLE diagnostics before any code
        // path serialises the definition through Jackson.
        val unrecognized = BpmnUnrecognizedElementScanner.scan(rendered.definition)
        if (unrecognized.isNotEmpty()) {
            return shortCircuitUnrecognized(request, graph, rendered, contract, unrecognized)
        }

        val normalisedDefinition = defaultFlowAssigner.assign(contract, rendered.definition)
        val normalisedRendered = rendered.copy(definition = normalisedDefinition)
        val initialMessages = promptFactory.initialMessages(request, normalisedDefinition)
        val evaluation = contractAwareValidator.evaluate(
            graph = graph,
            definition = normalisedDefinition,
            rendered = normalisedRendered,
            contract = contract,
            repairAttempts = 0,
        )
        val initialAttempt = BpmnRepairAttempt(
            attemptNumber = 1,
            repairAttempts = 0,
            graph = graph,
            evaluation = evaluation,
            messages = initialMessages,
        )
        val initialRecord = attemptRecordFactory.toRecord(initialAttempt)
        return BpmnRepairEvaluation(
            request = request,
            graph = graph,
            rendered = normalisedRendered,
            evaluation = evaluation,
            messages = initialMessages,
            history = BpmnAttemptHistory().append(initialRecord),
            contract = contract,
            repairAttempts = 0,
        )
    }

    /**
     * Re-stamp DefaultBranch, fingerprint-guard, update the graph, re-render, re-validate,
     * append to history, return the next blackboard.
     */
    fun revalidateAndAdvance(
        prior: BpmnRepairEvaluation,
        repaired: BpmnDefinition,
        appendedMessages: List<com.embabel.chat.Message>,
        promptText: String,
    ): BpmnRepairEvaluation {
        val stamped = defaultFlowAssigner.assign(prior.contract, repaired)
        val stampedFingerprint = fingerprints.definitionFingerprint(stamped)
        val priorRecord = prior.history.last
            ?: error("revalidateAndAdvance called with empty history — initialEvaluation must run first")
        guardAgainstNoProgress(stampedFingerprint, prior, priorRecord)

        val nextGraph = prior.graph.withUpdatedDefinition(stamped)
        var renderFailureMessage: String? = null
        val nextRendered = try {
            processGenerator.render(nextGraph)
        } catch (e: IllegalStateException) {
            renderFailureMessage = e.message ?: e.javaClass.simpleName
            null
        } catch (e: IllegalArgumentException) {
            renderFailureMessage = e.message ?: e.javaClass.simpleName
            null
        }

        val nextEvaluation = contractAwareValidator.evaluate(
            graph = nextGraph,
            definition = stamped,
            rendered = nextRendered,
            contract = prior.contract,
            renderFailureMessage = renderFailureMessage,
            repairAttempts = prior.repairAttempts + 1,
        )

        val nextMessages = prior.messages + appendedMessages
        val nextAttempt = BpmnRepairAttempt(
            attemptNumber = prior.history.size + 1,
            repairAttempts = prior.repairAttempts + 1,
            graph = nextGraph,
            evaluation = nextEvaluation,
            messages = nextMessages,
        )
        val nextRecord: BpmnAttemptRecord = attemptRecordFactory.toRecord(
            attempt = nextAttempt,
            repairPromptFingerprint = fingerprints.promptFingerprint(promptText),
        )

        guardAgainstStuckBlocking(nextEvaluation, nextRecord, priorRecord, nextAttempt.repairAttempts)

        return BpmnRepairEvaluation(
            request = prior.request,
            graph = nextGraph,
            rendered = nextRendered,
            evaluation = nextEvaluation,
            messages = nextMessages,
            history = prior.history.append(nextRecord),
            contract = prior.contract,
            repairAttempts = prior.repairAttempts + 1,
            renderFailureMessage = renderFailureMessage,
        )
    }

    private fun guardAgainstNoProgress(
        stampedFingerprint: String,
        prior: BpmnRepairEvaluation,
        priorRecord: BpmnAttemptRecord,
    ) {
        val reason = when {
            stampedFingerprint == priorRecord.definitionFingerprint ->
                "unchanged patch on repair attempt ${priorRecord.attemptNumber}"

            prior.history.containsDefinitionFingerprint(stampedFingerprint) ->
                "repeated invalid output on repair attempt ${priorRecord.attemptNumber}"

            else -> return
        }
        throw RepairReplans.signal(reason)
    }

    private fun guardAgainstStuckBlocking(
        nextEvaluation: dev.groknull.bpmner.conformance.BpmnEvaluation,
        nextRecord: BpmnAttemptRecord,
        priorRecord: BpmnAttemptRecord,
        repairAttempts: Int,
    ) {
        if (nextEvaluation.blockingDiagnostics.isNotEmpty() &&
            nextRecord.blockingDiagnosticFingerprint == priorRecord.blockingDiagnosticFingerprint
        ) {
            throw RepairReplans.signal(
                "unchanged blocking diagnostics after repair attempt $repairAttempts",
            )
        }
    }

    private fun shortCircuitUnrecognized(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        contract: ProcessContract,
        findings: List<UnrecognizedFinding>,
    ): BpmnRepairEvaluation {
        val diagnostics = findings.map { it.toDiagnostic() }
        logger.warn(
            "Repair short-circuited: {} unrecognized element(s) found pre-flight; LLM not invoked",
            findings.size,
        )
        val evaluation = BpmnEvaluation(
            definition = rendered.definition,
            rendered = rendered,
            diagnostics = diagnostics,
            globalDiagnostics = GlobalDiagnostics(diagnostics),
            validatedXml = null,
        )
        return BpmnRepairEvaluation(
            request = request,
            graph = graph,
            rendered = rendered,
            evaluation = evaluation,
            messages = emptyList(),
            history = BpmnAttemptHistory(),
            contract = contract,
            repairAttempts = 0,
        )
    }
}
