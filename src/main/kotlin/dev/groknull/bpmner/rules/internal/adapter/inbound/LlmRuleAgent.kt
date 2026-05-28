/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.adapter.inbound

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Export
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PromptRunner
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.api.RuleDiagnostic
import dev.groknull.bpmner.api.RuleMetadata
import dev.groknull.bpmner.api.RuleSeverity
import dev.groknull.bpmner.core.BpmnConfig
import dev.groknull.bpmner.rules.LlmRuleEvaluationRequest
import dev.groknull.bpmner.rules.LlmRuleEvaluationResult
import dev.groknull.bpmner.rules.LlmRuleSpec
import org.jmolecules.architecture.hexagonal.Application
import org.slf4j.LoggerFactory

/**
 * Evaluates `LlmCheckRule`-typed BPMN rules against a definition by delegating to an LLM
 * via Embabel's GOAP-shaped `PromptRunner`.
 *
 * Architectural notes (issue #240 review thread):
 *  - This is the **only** place in the rule subsystem that talks to an LLM. Deterministic
 *    rules (10 primitives in [dev.groknull.bpmner.rules.internal.domain.primitives]) and
 *    `CompositeCheck` all run as plain function calls below the GOAP boundary. LLM rules
 *    sit *on* the boundary as a proper `@Action` with `@AchievesGoal` so Embabel's planner
 *    can route the work, its observability sees the LLM call, and the `Actor<Persona>`
 *    configuration tunes the model role independently.
 *  - The agent's input ([LlmRuleEvaluationRequest]) is a public top-level type in `rules`
 *    so the Phase 2D `PklCatalogLoader` and any external caller (shell, HTTP, CI gate)
 *    can construct it.
 *  - Batching: rules are chunked by [BpmnConfig.lintBatchSize] (default 10) into one LLM
 *    round-trip per chunk. The agent itself owns the batching so the loader doesn't have
 *    to pre-shard.
 *  - No Embabel `Ai` bean is injected anywhere. The `PromptRunner` comes from the
 *    `OperationContext` passed into this `@Action`. An ArchUnit invariant guards the rest
 *    of the codebase against drift back to direct `Ai` injection.
 *  - [ObjectMapper] is injected so production runs use the Spring-managed mapper with all
 *    its configured modules (KotlinModule, JSR-310, etc.); the default arg falls back to
 *    a fresh `jacksonObjectMapper()` for unit tests that don't boot Spring.
 */
@Application
@Agent(description = "Evaluate LLM-judgement BPMN rules against a definition")
internal class LlmRuleAgent(
    private val config: BpmnConfig,
    private val objectMapper: ObjectMapper = jacksonObjectMapper(),
) {
    private val logger = LoggerFactory.getLogger(LlmRuleAgent::class.java)

    @AchievesGoal(
        description = "Evaluate LLM-judgement BPMN rules against a definition and report violations",
        export =
        Export(
            name = "lintLlmRules",
            remote = true,
            startingInputTypes = [LlmRuleEvaluationRequest::class],
        ),
    )
    @Action(description = "Evaluate the supplied LLM rules and report violations")
    fun evaluateLlmRules(
        request: LlmRuleEvaluationRequest,
        context: OperationContext,
    ): LlmRuleEvaluationResult {
        if (request.rules.isEmpty()) {
            logger.debug("LlmRuleAgent invoked with no rules; returning empty result")
            return LlmRuleEvaluationResult(diagnostics = emptyList())
        }
        val runner = config.linter.promptRunner(context)
        val definitionJson = serialize(request.definition)
        val diagnostics =
            request.rules
                .chunked(config.lintBatchSize)
                .flatMap { batch -> evaluateBatch(runner, definitionJson, batch) }
        logger.info(
            "LLM rule evaluation: {} rule(s) across {} batch(es) produced {} diagnostic(s)",
            request.rules.size,
            (request.rules.size + config.lintBatchSize - 1) / config.lintBatchSize,
            diagnostics.size,
        )
        return LlmRuleEvaluationResult(diagnostics = diagnostics)
    }

    private fun evaluateBatch(
        runner: PromptRunner,
        definitionJson: String,
        batch: List<LlmRuleSpec>,
    ): List<RuleDiagnostic> {
        val prompt = buildBatchPrompt(definitionJson, batch)
        // Phase 5 (#220): `createObject` returns non-null per Embabel's contract. If the LLM call
        // fails, an `InvalidLlmReturnFormatException` propagates here and `DefaultRuleEngine` wraps
        // it into a typed `rule-execution-failure` diagnostic via its outer `runCatching` —
        // observable failure beats silent "no issues found."
        val response = runner.createObject(prompt, LlmEvaluationResponse::class.java)
        val byRuleId = batch.associateBy { it.metadata.id }
        return response.violations.mapNotNull { violation ->
            val spec = byRuleId[violation.ruleId] ?: return@mapNotNull run {
                logger.warn(
                    "LLM returned violation for unknown ruleId '{}' (batch ids: {})",
                    violation.ruleId,
                    byRuleId.keys,
                )
                null
            }
            spec.metadata.diagnosticForViolation(violation)
        }
    }

    private fun buildBatchPrompt(
        definitionJson: String,
        batch: List<LlmRuleSpec>,
    ): String = buildString {
        appendLine("# BPMN linter — LLM rule evaluation")
        appendLine()
        appendLine("You will evaluate the following BPMN process against ${batch.size} rule(s).")
        appendLine("For each rule, decide whether the process violates it. If yes, emit one violation per")
        appendLine("offending element with the rule's id, the violating element id (when applicable), and a")
        appendLine("specific message anchored in the rule's intent.")
        appendLine()
        appendLine("## Rules to evaluate")
        appendLine()
        batch.forEach { spec ->
            appendLine("### ${spec.metadata.id}")
            appendLine("Intent: ${spec.metadata.intent}")
            appendLine("Guidance: ${spec.metadata.forAI}")
            appendLine("Prompt: ${spec.config.prompt}")
            spec.config.rubric?.let { appendLine("Rubric: $it") }
            appendLine()
        }
        appendLine("## Process definition (JSON)")
        appendLine()
        appendLine("```json")
        appendLine(definitionJson)
        appendLine("```")
        appendLine()
        appendLine("Return a structured response with the list of violations. Do not invent rule ids that")
        appendLine("are not in the list above. Do not flag rules that the process satisfies.")
    }

    /**
     * Fail fast on serialization error rather than send a placeholder prompt to the LLM
     * — burning tokens on a guaranteed-broken evaluation is worse than aborting the call.
     * `BpmnDefinition` is a stable Kotlin data class graph so this should never happen
     * outside genuinely-corrupt fixtures; the throw is here as a defence-in-depth signal.
     */
    private fun serialize(definition: Any): String = try {
        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(definition)
    } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
        logger.error("Failed to serialize BPMN definition for LLM prompt", e)
        throw IllegalStateException("Failed to serialize BPMN definition for LLM prompt", e)
    }

    /**
     * Builds the per-violation [RuleDiagnostic]. The `diagnosticCode` fallback to `id`
     * (when `errorMessages` has only the `"default"` key) matches the convention in
     * [dev.groknull.bpmner.rules.internal.domain.primitives.RuleMetadata.diagnosticCode]
     * — `diagnosticCode` is a stable per-check identifier used by `RuleProfile` severity
     * overrides, not a map key into `errorMessages`. See `api/RuleDiagnostic.kt` doc for
     * the codebase-wide semantic.
     *
     * **Guards multi-key LLM rules** (PR #249 G2): an `LlmCheckRule`-typed rule with two
     * or more non-`"default"` keys in `errorMessages` is ambiguous — `LlmRuleViolation`
     * doesn't carry a `diagnosticCode` field so the model can't signal which code
     * applies. The single-message assumption is the supported contract; we surface drift
     * as a focused `rule-config-error` rather than silently picking one code.
     */
    private fun RuleMetadata.diagnosticForViolation(violation: LlmRuleViolation): RuleDiagnostic {
        val nonDefaultKeys = errorMessages.keys.filter { it != "default" }
        if (nonDefaultKeys.size > 1) {
            return RuleDiagnostic(
                diagnosticCode = "rule-config-error",
                ruleId = id,
                severity = RuleSeverity.ERROR,
                message = "LlmCheckRule '$id' has multiple non-default errorMessages keys " +
                    "($nonDefaultKeys); LLM violations cannot disambiguate. Use exactly one code, " +
                    "or split into separate rules.",
                elementId = null,
            )
        }
        return RuleDiagnostic(
            diagnosticCode = nonDefaultKeys.singleOrNull() ?: id,
            ruleId = id,
            severity = severity,
            message = violation.message,
            elementId = violation.elementId,
        )
    }
}

/**
 * Structured LLM response shape. Internal to the agent because no caller outside the agent
 * cares how the model serialises its output — the public output type is
 * [LlmRuleEvaluationResult] carrying `RuleDiagnostic`s.
 *
 * `@JsonIgnoreProperties(ignoreUnknown = true)`: LLMs routinely emit auxiliary fields
 * (`reasoning`, `confidence`, `_meta`, etc.) even when given a strict response schema.
 * Without this annotation Jackson throws `UnrecognizedPropertyException` on the first
 * unknown field and the whole batch's diagnostics drop silently. The annotation makes
 * the response parser tolerant of extra fields it doesn't care about.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
internal data class LlmEvaluationResponse(
    val violations: List<LlmRuleViolation> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class LlmRuleViolation(
    val ruleId: String,
    val elementId: String?,
    val message: String,
)
