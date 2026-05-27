/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Condition
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.annotation.RequireNameMatch
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.Actor
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.embabel.agent.prompt.persona.Persona
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import dev.groknull.bpmner.api.RepairKind
import dev.groknull.bpmner.contract.ValidatedProcessContract
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.core.withUpdatedDefinition
import dev.groknull.bpmner.generation.BpmnRenderer
import dev.groknull.bpmner.generation.DefaultFlowAssigner
import dev.groknull.bpmner.repair.BpmnAttemptHistory
import dev.groknull.bpmner.repair.BpmnAttemptRecord
import dev.groknull.bpmner.repair.BpmnRepairAttempt
import dev.groknull.bpmner.repair.internal.domain.BpmnAttemptRecordFactory
import dev.groknull.bpmner.repair.internal.domain.BpmnContractAwareValidator
import dev.groknull.bpmner.repair.internal.domain.BpmnLocalModelFixHandlerRegistry
import dev.groknull.bpmner.repair.internal.domain.BpmnPatchApplicationPort
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairEvaluation
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPatch
import dev.groknull.bpmner.repair.internal.domain.BpmnRepairPromptPort
import dev.groknull.bpmner.repair.internal.domain.HandlerConfig
import dev.groknull.bpmner.repair.internal.domain.PatchApplicationResult
import dev.groknull.bpmner.rules.RuleRegistry
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnFingerprintService
import dev.groknull.bpmner.validation.BpmnLintRuleIds
import dev.groknull.bpmner.validation.BpmnRepairScope
import dev.groknull.bpmner.validation.BpmnValidationFailedEvent
import dev.groknull.bpmner.validation.BpmnValidationPassedEvent
import dev.groknull.bpmner.validation.ValidatedBpmnXml
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher

/**
 * Phase 4 (#219): repair-loop GOAP decomposition.
 *
 * The agent presents six `@Action`s and four `@Condition`s; Embabel's planner chains them
 * via `outputBinding = "repairEval"` so every action consumes and produces the same
 * [BpmnRepairEvaluation] blackboard. Cost-based A* picks the cheapest applicable action
 * each iteration: deterministic local fixes first (0.1), then label-scope LLM patches
 * (0.5), then structural LLM patches (0.7), then a full LLM rewrite (0.9). Each repair
 * action has `canRerun = true` so the planner can loop the same action while it keeps
 * making progress, and replan into the next cost tier when it can't.
 *
 * Failure modes are surfaced via the framework's status-typed exceptions:
 *  - `ProcessExecutionStuckException` — the planner found no applicable action.
 *  - `ProcessExecutionTerminatedException` — `Budget(actions = N)` exhausted before a goal
 *    was reached. Repeated no-progress (same fingerprint twice in a row) flips through
 *    `ReplanRequestedException` which costs an action each iteration; budget exhaustion is
 *    therefore the natural ceiling on stuck-but-not-stuck loops.
 *
 * Replaces the imperative `BpmnRefinementEngine.refine()` while-loop and the four
 * `BpmnRepairStrategy` implementations — see #219 for the side-by-side architecture table.
 */
@Application
@Agent(
    description = "Refine and repair generated BPMN 2.0 diagrams to ensure technical and semantic validity",
)
@Suppress("LongParameterList", "TooManyFunctions") // cohesive GOAP surface — actions, conditions, helper
internal class BpmnRepairAgent(
    private val bpmnRenderer: BpmnRenderer,
    private val contractAwareValidator: BpmnContractAwareValidator,
    private val attemptRecordFactory: BpmnAttemptRecordFactory,
    private val promptFactory: BpmnRepairPromptPort,
    private val patchApplier: BpmnPatchApplicationPort,
    private val fingerprints: BpmnFingerprintService,
    private val modelFixHandlerRegistry: BpmnLocalModelFixHandlerRegistry,
    private val ruleRegistry: RuleRegistry,
    private val defaultFlowAssigner: DefaultFlowAssigner,
    private val config: BpmnConfig,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val logger = LoggerFactory.getLogger(BpmnRepairAgent::class.java)

    // -----------------------------------------------------------------------------------
    // Actions

    /**
     * Initial validation — normalise the incoming definition (default-flow re-stamp), seed
     * the LLM message chain, and produce the first [BpmnRepairEvaluation] on the blackboard.
     * Cost is 0 so the planner always runs this first when GOAP needs a fresh blackboard.
     */
    @Action(
        post = ["hasDiagnostics", "diagnosticsResolved"],
        outputBinding = "repairEval",
    )
    fun validate(
        request: BpmnRequest,
        graph: LaidOutProcessGraph,
        rendered: RenderedBpmn,
        validatedContract: ValidatedProcessContract,
    ): BpmnRepairEvaluation {
        val contract = validatedContract.contract
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
     * Local deterministic fixes — dispatch any `LOCAL_MODEL_FIX` diagnostic to its declared
     * handler. Cheapest repair tier (0.1); always runs before any LLM call when applicable.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLocalFixable"],
        post = ["diagnosticsResolved"],
        cost = COST_DETERMINISTIC,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyDeterministicFixes(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        val attempt = repairEval.toAttempt()
        val applied = applyLocalModelFix(attempt)
            ?: throw RepairReplans.signal("no LOCAL_MODEL_FIX produced a candidate")
        logger.info("Local model fix applied on repair attempt {}", repairEval.repairAttempts + 1)
        return revalidateAndAdvance(
            prior = repairEval,
            repaired = applied.definition,
            appendedMessages = emptyList(),
            promptText = applied.reason,
        )
    }

    /**
     * LLM label-scope patch — fixes `BpmnRepairScope.LABEL` diagnostics via the
     * `labelRepairer` actor.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLlmLabelEligible"],
        post = ["diagnosticsResolved"],
        cost = COST_LLM_LABEL,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyLlmLabelPatch(
        @RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation,
        context: ActionContext,
    ): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        // `hasLlmLabelEligible` precondition guarantees at least one candidate — no runtime
        // emptiness check needed. ReplanRequestedException is reserved for genuine "I tried
        // and couldn't make progress" signals, not "I shouldn't have been selected."
        val candidates = repairEval.diagnostics.filter {
            eligibleForLlm(it) && it.repairScope == BpmnRepairScope.LABEL
        }
        val feedback = promptFactory.patchFeedback(repairEval.definition, candidates)
        return applyLlmPatch(repairEval, context, config.labelRepairer, feedback, "LLM label patch")
    }

    /**
     * LLM structural patch — fixes `OUTLINE` / `PHASE`-scope diagnostics via the
     * `patchRepairer` actor.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLlmStructuralEligible"],
        post = ["diagnosticsResolved"],
        cost = COST_LLM_STRUCTURAL,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyLlmStructuralPatch(
        @RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation,
        context: ActionContext,
    ): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        // `hasLlmStructuralEligible` precondition guarantees at least one OUTLINE/PHASE
        // candidate — the runtime filter is non-empty by construction.
        val candidates = repairEval.diagnostics.filter {
            eligibleForLlm(it) && (it.repairScope == BpmnRepairScope.OUTLINE || it.repairScope == BpmnRepairScope.PHASE)
        }
        val feedback = promptFactory.patchFeedback(repairEval.definition, candidates)
        return applyLlmPatch(repairEval, context, config.patchRepairer, feedback, "LLM structural patch")
    }

    /**
     * Full LLM rewrite — produces a fresh `BpmnDefinition` rather than a patch. Highest-cost
     * tier; only chosen when patch-scope repairs cannot resolve the remaining diagnostics.
     */
    @Action(
        pre = ["hasDiagnostics", "hasLlmEligible"],
        post = ["diagnosticsResolved"],
        cost = COST_LLM_REWRITE,
        canRerun = true,
        outputBinding = "repairEval",
    )
    fun applyFullLlmRewrite(
        @RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation,
        context: ActionContext,
    ): BpmnRepairEvaluation {
        publishAttemptEvent(repairEval)
        // `hasLlmEligible` precondition (`kind != UNFIXABLE`) exactly matches `eligibleForLlm` —
        // the filter is non-empty by construction.
        val candidates = repairEval.diagnostics.filter { eligibleForLlm(it) }
        val feedback = promptFactory.fullRepairFeedback(repairEval.toAttempt(), candidates)
        val runner = promptRunner(repairEval, context, config.rewriteRepairer)
        // `createObject` (not `createObjectIfPossible`) routes through `LlmOperations.createObject`,
        // which is what `EmbabelMockitoIntegrationTest.whenCreateObject` mocks. If the LLM
        // genuinely produces no structured result, `createObject` throws — we catch and replan.
        val repaired: BpmnDefinition = runCatching {
            runner.createObject(
                repairEval.messages + UserMessage(feedback),
                BpmnDefinition::class.java,
            )
        }.getOrNull()
            ?: throw RepairReplans.signal("LLM rewrite returned no structured definition")
        return revalidateAndAdvance(
            prior = repairEval,
            repaired = repaired,
            appendedMessages = listOf(UserMessage(feedback), AssistantMessage(repaired.toString())),
            promptText = feedback,
        )
    }

    /**
     * Terminal action — package the validated XML and publish the success event.
     */
    @Action(pre = ["diagnosticsResolved"])
    @AchievesGoal(
        description = "Refine and repair generated BPMN 2.0 diagrams to ensure technical and semantic validity",
        export = Export(
            name = "repairBpmn",
            remote = true,
            startingInputTypes = [
                BpmnRequest::class,
                LaidOutProcessGraph::class,
                RenderedBpmn::class,
                ValidatedProcessContract::class,
            ],
        ),
    )
    fun finalize(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): ValidatedBpmnXml {
        logAdvisoryDiagnostics(repairEval.evaluation.advisoryDiagnostics, repairEval.repairAttempts)
        // The pipeline only stamps `validatedXml` when all structural diagnostics clear AND the
        // rendered XML survived. In some pipeline configurations (notably contract-aware
        // fidelity routes) a successful render with zero diagnostics still leaves
        // `validatedXml` null — diagnosticsResolved guards the action at the planner level
        // but the artifact still needs synthesising from the rendered output we already hold.
        val xml = repairEval.evaluation.validatedXml
            ?: repairEval.rendered?.xml
            ?: error("finalize fired with no validated XML and no rendered XML on the blackboard")
        val result = ValidatedBpmnXml(
            definition = repairEval.definition,
            xml = xml,
            diagnostics = repairEval.diagnostics,
            repairAttempts = repairEval.repairAttempts,
        )
        eventPublisher.publishEvent(
            BpmnValidationPassedEvent(repairEval.request, result.xml, repairEval.repairAttempts),
        )
        return result
    }

    // -----------------------------------------------------------------------------------
    // Conditions — every `repairEval` parameter carries `@RequireNameMatch` so all bindings
    // resolve to the same blackboard instance the actions produced.

    // The condition bodies delegate to extension-style properties on `BpmnRepairEvaluation`
    // (see `hasDiagnostics`, `hasLocalFixable`, …) so each signature stays short enough to
    // satisfy both ktlint's auto-inliner and detekt's MaxLineLength=130.

    @Condition
    fun hasDiagnostics(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): Boolean = repairEval.hasDiagnostics

    /**
     * Logical opposite of [hasDiagnostics]. Intentionally narrower than
     * [dev.groknull.bpmner.validation.BpmnEvaluation.isSuccessful], which also requires
     * `validatedXml` to be set — that's a downstream concern handled by [finalize], which
     * synthesises the XML from `rendered` when the pipeline didn't store one. Pinning the
     * planner condition to the diagnostic count alone keeps `diagnosticsResolved` the
     * strict inverse of `hasDiagnostics`.
     */
    @Condition
    fun diagnosticsResolved(@RequireNameMatch("repairEval") repair: BpmnRepairEvaluation): Boolean = repair.diagnosticsResolved

    @Condition
    fun hasLocalFixable(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): Boolean = repairEval.hasLocalFixable

    @Condition
    fun hasLlmEligible(@RequireNameMatch("repairEval") repairEval: BpmnRepairEvaluation): Boolean = repairEval.hasLlmEligible

    @Condition
    fun hasLlmLabelEligible(@RequireNameMatch("repairEval") repair: BpmnRepairEvaluation): Boolean = repair.hasLlmLabelEligible

    @Condition
    fun hasLlmStructuralEligible(@RequireNameMatch("repairEval") r: BpmnRepairEvaluation): Boolean = r.hasLlmStructuralEligible

    // -----------------------------------------------------------------------------------
    // Helpers

    /**
     * The plan's [#revalidateAndAdvance]: re-stamp DefaultBranch, fingerprint-guard, update
     * the graph, re-render, re-validate, append to history, return the next blackboard.
     *
     * Throws `ReplanRequestedException` (via [RepairReplans.signal]) when:
     *  - the post-stamp definition fingerprint equals the previous one — the repair
     *    produced no observable change.
     *  - the post-stamp definition fingerprint has been seen earlier in the history — a
     *    cycle. Both push the planner toward a different (higher-cost) action next pass.
     */
    @Suppress("LongMethod") // mirrors the engine's evaluateNextAttempt + validateRepairEffect
    private fun revalidateAndAdvance(
        prior: BpmnRepairEvaluation,
        repaired: BpmnDefinition,
        appendedMessages: List<com.embabel.chat.Message>,
        promptText: String,
    ): BpmnRepairEvaluation {
        val stamped = defaultFlowAssigner.assign(prior.contract, repaired)
        val stampedFingerprint = fingerprints.definitionFingerprint(stamped)
        val priorRecord = prior.history.last
            ?: error("revalidateAndAdvance called with empty history — validate() must run first")
        guardAgainstNoProgress(stampedFingerprint, prior, priorRecord)

        val nextGraph = prior.graph.withUpdatedDefinition(stamped)
        var renderFailureMessage: String? = null
        val nextRendered = try {
            bpmnRenderer.render(nextGraph)
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

    /**
     * Throws `ReplanRequestedException` if the repair produced no observable change (same
     * fingerprint as the previous attempt) or if the resulting definition has already been
     * seen earlier in the history (cycle). Either case pushes the planner to a different,
     * more expensive action next iteration.
     */
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

    /**
     * Legacy engine's stuck-blocking guard: same blocking fingerprint twice in a row means
     * the repair didn't move a blocking diagnostic. Forces GOAP to try a different action.
     */
    private fun guardAgainstStuckBlocking(
        nextEvaluation: dev.groknull.bpmner.validation.BpmnEvaluation,
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

    private fun applyLlmPatch(
        repairEval: BpmnRepairEvaluation,
        operationContext: OperationContext,
        actor: Actor<Persona>,
        feedback: String,
        patchTypeName: String,
    ): BpmnRepairEvaluation {
        val runner = promptRunner(repairEval, operationContext, actor)
        // See applyFullLlmRewrite for why this uses `createObject` rather than
        // `createObjectIfPossible` — alignment with what `whenCreateObject` mocks.
        val patch: BpmnRepairPatch = runCatching {
            runner.createObject(
                repairEval.messages + UserMessage(feedback),
                BpmnRepairPatch::class.java,
            )
        }.getOrNull()
            ?: throw RepairReplans.signal("$patchTypeName returned no structured patch")
        val application = patchApplier.apply(repairEval.definition, patch)
        val success = patchSuccessOrReplan(application, patchTypeName)
        return revalidateAndAdvance(
            prior = repairEval,
            repaired = success.definition,
            appendedMessages = listOf(UserMessage(feedback), AssistantMessage(patch.toString())),
            promptText = feedback,
        )
    }

    /**
     * Unwraps a [PatchApplicationResult] into its [PatchApplicationResult.Success] form or
     * throws `ReplanRequestedException`. Centralising the throw here means [applyLlmPatch]
     * has a single throw site, satisfying detekt's `ThrowsCount` without flattening the
     * sealed-result `when`.
     */
    private fun patchSuccessOrReplan(
        application: PatchApplicationResult,
        patchTypeName: String,
    ): PatchApplicationResult.Success {
        val reason = when (application) {
            is PatchApplicationResult.Success -> return application

            is PatchApplicationResult.Failure -> {
                logger.warn("{} application failed: {}", patchTypeName, application.reason)
                "$patchTypeName application failed: ${application.reason}"
            }

            PatchApplicationResult.NoOp -> "$patchTypeName produced no-op"
        }
        throw RepairReplans.signal(reason)
    }

    private fun applyLocalModelFix(attempt: BpmnRepairAttempt): LocalFixApplied? = attempt.diagnostics
        .asSequence()
        .mapNotNull { buildLocalFixCandidate(attempt.definition, it) }
        .firstNotNullOfOrNull { tryApplyLocalFix(attempt.definition, it) }

    private fun buildLocalFixCandidate(definition: BpmnDefinition, diagnostic: BpmnDiagnostic): LocalFixCandidate? {
        if (diagnostic.kind != RepairKind.LOCAL_MODEL_FIX) return null
        val handlerName = diagnostic.fixHandler ?: return null
        val elementId = diagnostic.elementId ?: return null
        val handler = modelFixHandlerRegistry.lookup(handlerName) ?: return null
        val ops = handler.buildPatch(definition, elementId, handlerConfigFor(diagnostic))
        if (ops.isEmpty()) return null
        val reason = "LOCAL_MODEL_FIX: $handlerName on $elementId"
        return LocalFixCandidate(
            handlerName = handlerName,
            elementId = elementId,
            patch = BpmnRepairPatch(operations = ops, reason = reason),
            reason = reason,
        )
    }

    private fun tryApplyLocalFix(
        definition: BpmnDefinition,
        candidate: LocalFixCandidate,
    ): LocalFixApplied? = when (val applied = patchApplier.apply(definition, candidate.patch)) {
        is PatchApplicationResult.Success -> LocalFixApplied(applied.definition, candidate.reason)

        is PatchApplicationResult.Failure -> {
            logger.warn(
                "Local model fix produced invalid patch; trying next diagnostic. handler={}, elementId={}, reason={}",
                candidate.handlerName,
                candidate.elementId,
                applied.reason,
            )
            null
        }

        PatchApplicationResult.NoOp -> null
    }

    private data class LocalFixCandidate(
        val handlerName: String,
        val elementId: String,
        val patch: BpmnRepairPatch,
        val reason: String,
    )

    private fun handlerConfigFor(diagnostic: BpmnDiagnostic): HandlerConfig {
        val ruleId = diagnostic.rule?.let(BpmnLintRuleIds::bareRuleId) ?: return HandlerConfig.EMPTY
        val meta = ruleRegistry.ruleByIdOrAlias(ruleId)?.metadata ?: return HandlerConfig.EMPTY
        return HandlerConfig(staticConfig = meta.staticConfig, replacementMap = meta.repair.replacementMap)
    }

    private fun promptRunner(
        repairEval: BpmnRepairEvaluation,
        operationContext: OperationContext,
        actor: Actor<Persona>,
    ): PromptRunner {
        val baseRunner = actor.promptRunner(operationContext).withPromptContributor(repairEval.request)
        val docsPrompt = promptFactory.lintRuleDocsPrompt(repairEval.diagnostics)
        return if (docsPrompt != null) baseRunner.withPromptContributor(docsPrompt) else baseRunner
    }

    private fun publishAttemptEvent(repairEval: BpmnRepairEvaluation) {
        contractAwareValidator.logDiagnosticSummary(repairEval.evaluation.globalDiagnostics.diagnostics)
        eventPublisher.publishEvent(
            BpmnValidationFailedEvent(
                request = repairEval.request,
                xml = repairEval.rendered?.xml ?: "",
                diagnostics = repairEval.diagnostics,
                attemptNumber = repairEval.history.size,
                repairAttempts = repairEval.repairAttempts,
            ),
        )
    }

    private fun logAdvisoryDiagnostics(advisory: List<BpmnDiagnostic>, repairAttempts: Int) {
        if (advisory.isEmpty()) return
        logger.info(
            "Pipeline succeeded after {} repair attempt(s) with {} advisory diagnostic(s) remaining",
            repairAttempts,
            advisory.size,
        )
    }

    private data class LocalFixApplied(val definition: BpmnDefinition, val reason: String)

    /** Sole post-Phase-2 LLM eligibility predicate — cost ordering and the fingerprint guard handle priority. */
    private fun eligibleForLlm(diagnostic: BpmnDiagnostic): Boolean = diagnostic.kind != RepairKind.UNFIXABLE

    private companion object {
        const val COST_DETERMINISTIC = 0.1
        const val COST_LLM_LABEL = 0.5
        const val COST_LLM_STRUCTURAL = 0.7
        const val COST_LLM_REWRITE = 0.9
    }
}

/**
 * Bridge to the Embabel framework's replan signal. The agent calls [signal] from the
 * `revalidateAndAdvance` helper (fingerprint-cycle and blocking-unchanged guards) and from
 * the patch-failure branches of each LLM action — the planner catches it and picks a
 * different applicable action next iteration.
 */
private object RepairReplans {
    fun signal(reason: String): RuntimeException = com.embabel.agent.core.ReplanRequestedException(reason)
}
