/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.mapping

import dev.groknull.bpmner.pkl.generated.CheckPrimitive as PklCheckPrim
import dev.groknull.bpmner.rules.LlmCheckRuleConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.CardinalityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityMode
import dev.groknull.bpmner.rules.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PoolLabelMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PropertyPatternCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.RequiredAssociationCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.RequiredPropertyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.SubCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.TopologyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.TopologyMode
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.VocabularyMode

/**
 * Result of mapping a Pkl-generated `CheckConfig` into a typed Kotlin config. Each Pkl rule's
 * `checkPrimitive` lands in one of three families:
 *
 *  - [Deterministic] — the 10 structural primitives. Consumed by the primitive check classes
 *    via `SubCheckEvaluator` or direct evaluate calls in [dev.groknull.bpmner.rules.internal.domain.primitives].
 *  - [Composite] — `CompositeCheck` composes deterministic sub-checks. Each sub-check is
 *    itself a [Deterministic]; mixing in [Composite] (nesting) or [Llm] is rejected here.
 *  - [Llm] — LLM-judged check, routed through `LlmRuleAgent` rather than the deterministic
 *    engine.
 *
 * Pkl-codegen-java emits `CheckConfig` as an open abstract class (sealed unions aren't
 * supported, see apple/pkl#22). [CheckConfigMapper] is the single point where the open
 * Java hierarchy collapses back into Kotlin's sealed [DeterministicCheckConfig] so the rest
 * of the loader can rely on exhaustive `when`.
 */
internal sealed interface MappedCheck {
    data class Deterministic(val config: DeterministicCheckConfig) : MappedCheck

    data class Composite(val config: CompositeCheckConfig) : MappedCheck

    data class Llm(val config: LlmCheckRuleConfig) : MappedCheck
}

internal object CheckConfigMapper {
    fun map(generated: PklCheckPrim.CheckConfig): MappedCheck = when (generated) {
        is PklCheckPrim.RequiredPropertyCheck ->
            MappedCheck.Deterministic(
                RequiredPropertyCheckConfig(property = generated.property),
            )

        is PklCheckPrim.PropertyPatternCheck ->
            MappedCheck.Deterministic(
                PropertyPatternCheckConfig(
                    property = generated.property,
                    pattern = generated.pattern,
                    patternDescription = generated.patternDescription,
                ),
            )

        is PklCheckPrim.VocabularyCheck ->
            MappedCheck.Deterministic(
                VocabularyCheckConfig(
                    property = generated.property,
                    mode = vocabularyMode(generated.mode),
                    words = generated.words,
                ),
            )

        is PklCheckPrim.RequiredAssociationCheck ->
            MappedCheck.Deterministic(
                RequiredAssociationCheckConfig(
                    association = generated.association,
                    sourceTypes = generated.sourceTypes,
                    targetTypes = generated.targetTypes,
                ),
            )

        is PklCheckPrim.TopologyCheck ->
            MappedCheck.Deterministic(
                TopologyCheckConfig(
                    topology = topologyMode(generated.topology),
                    minIncoming = generated.minIncoming?.toInt(),
                    maxIncoming = generated.maxIncoming?.toInt(),
                    minOutgoing = generated.minOutgoing?.toInt(),
                    maxOutgoing = generated.maxOutgoing?.toInt(),
                ),
            )

        is PklCheckPrim.ConnectivityCheck ->
            MappedCheck.Deterministic(
                ConnectivityCheckConfig(
                    mode = connectivityMode(generated.mode),
                    sourceTypes = generated.sourceTypes,
                    targetTypes = generated.targetTypes,
                ),
            )

        is PklCheckPrim.PairingCheck ->
            MappedCheck.Deterministic(
                PairingCheckConfig(
                    mode = pairingMode(generated.mode),
                    left = generated.left,
                    right = generated.right,
                ),
            )

        is PklCheckPrim.CardinalityCheck ->
            MappedCheck.Deterministic(
                CardinalityCheckConfig(
                    element = generated.element,
                    min = generated.min?.toInt(),
                    max = generated.max?.toInt(),
                ),
            )

        is PklCheckPrim.PoolLabelCheck ->
            MappedCheck.Deterministic(
                PoolLabelCheckConfig(mode = poolLabelMode(generated.mode)),
            )

        is PklCheckPrim.ElementConstraintCheck ->
            MappedCheck.Deterministic(
                ElementConstraintCheckConfig(
                    element = generated.element,
                    mode = elementConstraintMode(generated.mode),
                    constraints = generated.constraints,
                ),
            )

        is PklCheckPrim.CompositeCheck ->
            MappedCheck.Composite(
                CompositeCheckConfig(
                    targetTypes = generated.targetTypes,
                    subChecks = generated.subChecks.map { subCheck ->
                        val mappedSub = map(subCheck.config)
                        require(mappedSub is MappedCheck.Deterministic) {
                            "CompositeCheck sub-check '${subCheck.diagnosticCode}' must be deterministic; " +
                                "got ${mappedSub::class.simpleName}"
                        }
                        SubCheckConfig(
                            diagnosticCode = subCheck.diagnosticCode,
                            config = mappedSub.config,
                        )
                    },
                ),
            )

        is PklCheckPrim.LlmCheckRule ->
            MappedCheck.Llm(
                LlmCheckRuleConfig(prompt = generated.prompt, rubric = generated.rubric),
            )

        else -> error("Unknown CheckConfig subtype: ${generated::class.qualifiedName}")
    }

    private fun vocabularyMode(raw: String): VocabularyMode = when (raw) {
        "REQUIRE" -> VocabularyMode.REQUIRE
        "FORBID" -> VocabularyMode.FORBID
        else -> error("Unknown VocabularyCheck.mode '$raw' (expected REQUIRE or FORBID)")
    }

    private fun topologyMode(raw: String): TopologyMode = when (raw) {
        "NO_FAKE_JOIN" -> TopologyMode.NO_FAKE_JOIN
        "NO_SUPERFLUOUS" -> TopologyMode.NO_SUPERFLUOUS
        "NO_JOIN_FORK" -> TopologyMode.NO_JOIN_FORK
        "CONVERGING_UNNAMED" -> TopologyMode.CONVERGING_UNNAMED
        else -> error("Unknown TopologyCheck.topology '$raw'")
    }

    private fun connectivityMode(raw: String): ConnectivityMode = when (raw) {
        "NO_INCOMING" -> ConnectivityMode.NO_INCOMING
        "FLOWS_NAMED" -> ConnectivityMode.FLOWS_NAMED
        "WITHIN_POOL" -> ConnectivityMode.WITHIN_POOL
        "ACROSS_POOLS" -> ConnectivityMode.ACROSS_POOLS
        else -> error("Unknown ConnectivityCheck.mode '$raw'")
    }

    private fun pairingMode(raw: String): PairingMode = when (raw) {
        "ERROR_END_BOUNDARY" -> PairingMode.ERROR_END_BOUNDARY
        "LINK_PAIRING" -> PairingMode.LINK_PAIRING
        "MESSAGE_START_FLOW" -> PairingMode.MESSAGE_START_FLOW
        else -> error("Unknown PairingCheck.mode '$raw'")
    }

    private fun poolLabelMode(raw: String): PoolLabelMode = runCatching {
        PoolLabelMode.valueOf(raw)
    }.getOrElse {
        error("Unknown PoolLabelCheck.mode '$raw' (expected one of ${PoolLabelMode.values().joinToString { it.name }})")
    }

    private fun elementConstraintMode(raw: String): ElementConstraintMode = when (raw) {
        "ALLOWED_ELEMENT_SUBSET" -> ElementConstraintMode.ALLOWED_ELEMENT_SUBSET
        "TIMER_EXPRESSION" -> ElementConstraintMode.TIMER_EXPRESSION
        "PARALLEL_GATEWAY_STRUCTURE" -> ElementConstraintMode.PARALLEL_GATEWAY_STRUCTURE
        "EVENT_BASED_GATEWAY_DIRECT_EVENTS" -> ElementConstraintMode.EVENT_BASED_GATEWAY_DIRECT_EVENTS
        else -> error("Unknown ElementConstraintCheck.mode '$raw'")
    }
}
