/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

import com.embabel.agent.api.common.Actor
import com.embabel.agent.config.models.anthropic.withAnthropicCaching
import com.embabel.agent.prompt.persona.Persona
import com.embabel.common.ai.model.LlmOptions
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

// `@Validated` is REQUIRED for `@ConfigurationProperties` to honour `@field:Valid` / `@Min` /
// `@Max` constraints — without it, Spring Boot binds values but silently skips JSR-380
// validation at startup.
@Validated
@ConfigurationProperties("bpmner")
data class BpmnConfig(
    val generator: Actor<Persona> = DEFAULT_GENERATOR,
    val repairer: Actor<Persona> = DEFAULT_REPAIRER,
    val readinessAssessor: Actor<Persona> = DEFAULT_READINESS_ASSESSOR,
    @field:Valid
    val readiness: BpmnReadinessConfig = BpmnReadinessConfig(),
    val contractExtractor: Actor<Persona> = DEFAULT_CONTRACT_EXTRACTOR,
    val contract: BpmnContractConfig = BpmnContractConfig(),
    val alignmentValidator: Actor<Persona> = DEFAULT_ALIGNMENT_VALIDATOR,
    @field:Valid
    val alignment: BpmnAlignmentConfig = BpmnAlignmentConfig(),
    val logging: BpmnLoggingConfig = BpmnLoggingConfig(),
    val rules: BpmnRulesConfig = BpmnRulesConfig(),
    @field:Valid
    val budget: BpmnBudgetConfig = BpmnBudgetConfig(),
    val labelRepairer: Actor<Persona> = DEFAULT_LABEL_REPAIRER,
    val patchRepairer: Actor<Persona> = DEFAULT_PATCH_REPAIRER,
    val rewriteRepairer: Actor<Persona> = DEFAULT_REWRITE_REPAIRER,
    val linter: Actor<Persona> = DEFAULT_LINTER,
    @field:Min(1)
    val lintBatchSize: Int = DEFAULT_LINT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_LINT_BATCH_SIZE = 10

        // Enable Anthropic prompt caching on every role. The pipeline's system message
        // (persona + request contribution + JSON schema) is stable across a run, and the repair
        // loop re-issues a near-identical system prompt every iteration — both ideal cache targets
        // (reads cost 10% of input tokens). systemPrompt caches the stable system prefix; tools
        // caches the JSON schema (the largest single block). TTL is the 5-minute default, which
        // covers a full pipeline run plus its repair iterations; raise to 1 hour if cross-run reuse
        // within an interactive session proves common.
        //
        // Unconditional by design: the caching config rides on LlmOptions and is read only by
        // Anthropic's options converter, so it is inert under non-Anthropic profiles —
        // no provider gate needed.
        private fun cachingLlm(role: String): LlmOptions = LlmOptions.withLlmForRole(role)
            .withAnthropicCaching(systemPrompt = true, tools = true)

        private const val CONCISE_AND_EXACT = "concise and exact"
        private const val SOURCE_GROUNDED_VOICE = "specific and evidence-grounded"

        val DEFAULT_GENERATOR =
            Actor(
                persona =
                Persona(
                    name = "BPMN Designer",
                    persona = "You are an expert BPMN 2.0 process modeller",
                    objective =
                    "Create a valid, well-structured BPMN process definition from a workflow description",
                    voice = "precise and thorough",
                ),
                llm = cachingLlm("generator"),
            )

        val DEFAULT_LABEL_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Label Copy Editor",
                    persona = "You are a fast, detail-oriented BPMN copy editor",
                    objective =
                    "Fix naming and label capitalization rules by providing targeted node and edge patches",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repair-label"),
            )

        val DEFAULT_PATCH_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Patch Repair Specialist",
                    persona = "You are a strict BPMN 2.0 graph topology validator and patch expert",
                    objective =
                    "Fix structural and routing validation errors by adding or removing" +
                        " specific elements without rewriting the whole definition",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repair-patch"),
            )

        val DEFAULT_REWRITE_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Full Rewrite Specialist",
                    persona = "You are an expert BPMN 2.0 validator who specializes in holistic process restructuring",
                    objective =
                    "Fix complex, cascading validation errors by rewriting the complete BPMN definition",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repair-rewrite"),
            )

        val DEFAULT_REPAIRER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Repair Specialist",
                    persona = "You are a strict BPMN 2.0 validator and repair expert",
                    objective =
                    "Fix every validation error in the BPMN definition" +
                        " and return the complete corrected object",
                    voice = CONCISE_AND_EXACT,
                ),
                llm = cachingLlm("repairer"),
            )
        val DEFAULT_READINESS_ASSESSOR =
            Actor(
                persona =
                Persona(
                    name = "BPMN Readiness Assessor",
                    persona =
                    "You are a conservative workflow readiness reviewer. You accept any" +
                        " repeatable sequenced workflow — business, automated, technical, scientific," +
                        " or personal — and you only block inputs that genuinely lack workflow structure",
                    objective =
                    "Assess whether source text contains enough grounded process detail" +
                        " for BPMN generation without inventing missing facts",
                    voice = SOURCE_GROUNDED_VOICE,
                ),
                llm = cachingLlm("readiness-assessor"),
            )
        val DEFAULT_CONTRACT_EXTRACTOR =
            Actor(
                persona =
                Persona(
                    name = "Process Contract Extractor",
                    persona =
                    "You are a conservative workflow analyst who extracts source-grounded process" +
                        " contracts from any kind of sequenced workflow (business, automated, technical," +
                        " scientific, or personal)",
                    objective =
                    "Produce a typed ProcessContract whose every element is traceable to the source" +
                        " input, an assessment evidence id, a clarification answer, or an explicit" +
                        " assumption; never invent facts that are not grounded",
                    voice = SOURCE_GROUNDED_VOICE,
                ),
                llm = cachingLlm("contract-extractor"),
            )
        val DEFAULT_ALIGNMENT_VALIDATOR =
            Actor(
                persona =
                Persona(
                    name = "BPMN Alignment Guard",
                    persona = "You are a strict BPMN semantic validator",
                    objective =
                    "Verify that generated BPMN process matches the process contract exactly;" +
                        " flag any invented tasks, missing branches, or unsupported end states",
                    voice = "critical and precise",
                ),
                llm = cachingLlm("alignment-validator"),
            )
        val DEFAULT_LINTER =
            Actor(
                persona =
                Persona(
                    name = "BPMN Linter",
                    persona =
                    "You are a meticulous BPMN 2.0 quality reviewer focused on labelling," +
                        " clarity, and modelling conventions",
                    objective =
                    "Evaluate LLM-judgement BPMN rules against a definition and report" +
                        " specific, element-anchored violations — never invent failures, never" +
                        " skip rules that apply",
                    voice = SOURCE_GROUNDED_VOICE,
                ),
                llm = cachingLlm("lint"),
            )
    }
}

data class BpmnReadinessConfig(
    @field:Min(0)
    @field:Max(MAX_PERCENT_SCORE)
    val readyThreshold: Int = 75,
    @field:Min(1)
    val minimumActivityCount: Int = 2,
    @field:Min(1)
    val maxClarificationQuestions: Int = 5,
) {
    companion object {
        const val MAX_PERCENT_SCORE = 100L
    }
}

data class BpmnContractConfig(
    val maxAssumptions: Int = 10,
)

data class BpmnAlignmentConfig(
    @field:Min(0)
    val maxAssumptions: Int = 3,
    val blockOnUnsupportedElements: Boolean = true,
    val blockOnMissingContractItems: Boolean = true,
)

data class BpmnLoggingConfig(
    val dir: String = "logs",
    val dumpArtifacts: Boolean = false,
    val artifactPreviewLength: Int = DEFAULT_ARTIFACT_PREVIEW_LENGTH,
) {
    companion object {
        const val DEFAULT_ARTIFACT_PREVIEW_LENGTH = 8000
    }
}

/**
 * GOAP action budgets per Embabel [ProcessOptions]. Generation and repair share a single budget
 * because the repair loop chains into the generation goal in one GOAP plan; lowering [generation] below today's ceiling
 * risks budget exhaustion on inputs that need substantial repair before reaching the
 * `generateBpmn` goal. Readiness is a separate, much smaller pipeline (no repair loop).
 *
 * [maxRepairIterations] bounds the [BpmnRepairLoop]: the loop exits as soon as there are no
 * blocking diagnostics or the iteration count reaches this ceiling, whichever comes first.
 * Keep this well below [generation] so a stuck repair loop cannot exhaust the entire GOAP budget.
 */
data class BpmnBudgetConfig(
    @field:Min(1)
    val generation: Int = 100,
    @field:Min(1)
    val readiness: Int = 20,
    @field:Min(1)
    val maxRepairIterations: Int = DEFAULT_MAX_REPAIR_ITERATIONS,
) {
    companion object {
        const val DEFAULT_MAX_REPAIR_ITERATIONS = 5
    }
}

data class BpmnRulesConfig(
    // Modeller-owned lint convention source. Defaults to the packaged
    // `modulepath:/linter/pkl/bpmner.pkl`; set `bpmner.rules.config-uri` to a `file:` URI to load
    // team-specific word lists. Rule profile and per-rule severity overrides are read from
    // `bpmner.pkl` as part of [BpmnerLintConfig] (fields `profile` and `severityOverrides`).
    val configUri: String? = null,
)
