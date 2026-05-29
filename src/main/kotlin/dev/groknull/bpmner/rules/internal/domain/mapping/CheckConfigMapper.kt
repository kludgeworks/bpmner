/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.mapping

import dev.groknull.bpmner.rules.LlmCheckRuleConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.CardinalityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.CompositeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ConnectivityMode
import dev.groknull.bpmner.rules.internal.domain.primitives.DeterministicCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.ElementConstraintMode
import dev.groknull.bpmner.rules.internal.domain.primitives.GrammaticalShape
import dev.groknull.bpmner.rules.internal.domain.primitives.GrammaticalShapeCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.LemmaCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.LemmaMode
import dev.groknull.bpmner.rules.internal.domain.primitives.NlpPosTag
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PairingMode
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechCheckConfig
import dev.groknull.bpmner.rules.internal.domain.primitives.PartOfSpeechMode
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
import dev.groknull.bpmner.pkl.CheckPrimitive as PklCheckPrim

/**
 * Result of mapping a Pkl-generated `CheckConfig` into a typed Kotlin config. Each Pkl rule's
 * `checkPrimitive` lands in one of three families:
 *
 *  - [Deterministic] — the structural and NLP-aware primitives. Consumed by the primitive
 *    check classes via `SubCheckEvaluator` or direct evaluate calls in
 *    [dev.groknull.bpmner.rules.internal.domain.primitives].
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
        is PklCheckPrim.CompositeCheck -> MappedCheck.Composite(generated.toCompositeConfig())
        is PklCheckPrim.LlmCheckRule -> MappedCheck.Llm(generated.toLlmConfig())
        else -> MappedCheck.Deterministic(generated.toDeterministicConfig())
    }
}

// ---------------------------------------------------------------------------------------
// Per-family mappers, split so each function's branch count stays well under detekt's
// CyclomaticComplexMethod threshold. The Pkl→Kotlin mapping is just constructor calls
// per primitive, so each branch is one line.

private fun PklCheckPrim.CheckConfig.toDeterministicConfig(): DeterministicCheckConfig = toStructuralConfig()
    ?: toNlpConfig()
    ?: error("Unknown CheckConfig subtype: ${this::class.java.name}")

private fun PklCheckPrim.CheckConfig.toStructuralConfig(): DeterministicCheckConfig? = when (this) {
    is PklCheckPrim.RequiredPropertyCheck -> RequiredPropertyCheckConfig(property)

    is PklCheckPrim.PropertyPatternCheck -> PropertyPatternCheckConfig(
        property = property,
        pattern = pattern,
        patternDescription = patternDescription,
        forbiddenVocabulary = forbiddenVocabulary,
        allowedVocabulary = allowedVocabulary,
    )

    is PklCheckPrim.VocabularyCheck -> VocabularyCheckConfig(
        property = property,
        mode = parseMode<VocabularyMode>(mode, "VocabularyCheck.mode"),
        words = words,
    )

    is PklCheckPrim.RequiredAssociationCheck -> RequiredAssociationCheckConfig(association, sourceTypes, targetTypes)

    is PklCheckPrim.TopologyCheck -> TopologyCheckConfig(
        topology = parseMode<TopologyMode>(topology, "TopologyCheck.topology"),
        minIncoming = minIncoming?.toInt(),
        maxIncoming = maxIncoming?.toInt(),
        minOutgoing = minOutgoing?.toInt(),
        maxOutgoing = maxOutgoing?.toInt(),
    )

    is PklCheckPrim.ConnectivityCheck -> ConnectivityCheckConfig(
        mode = parseMode<ConnectivityMode>(mode, "ConnectivityCheck.mode"),
        sourceTypes = sourceTypes,
        targetTypes = targetTypes,
    )

    is PklCheckPrim.PairingCheck -> PairingCheckConfig(parseMode<PairingMode>(mode, "PairingCheck.mode"), left, right)

    is PklCheckPrim.CardinalityCheck -> CardinalityCheckConfig(element, min?.toInt(), max?.toInt())

    is PklCheckPrim.PoolLabelCheck -> PoolLabelCheckConfig(parseMode<PoolLabelMode>(mode, "PoolLabelCheck.mode"))

    is PklCheckPrim.ElementConstraintCheck -> ElementConstraintCheckConfig(
        element = element,
        mode = parseMode<ElementConstraintMode>(mode, "ElementConstraintCheck.mode"),
        constraints = constraints,
    )

    else -> null
}

private fun PklCheckPrim.CheckConfig.toNlpConfig(): DeterministicCheckConfig? = when (this) {
    is PklCheckPrim.PartOfSpeechCheck -> PartOfSpeechCheckConfig(
        property = property,
        mode = parseMode<PartOfSpeechMode>(mode, "PartOfSpeechCheck.mode"),
        posClass = parseMode<NlpPosTag>(posClass, "PartOfSpeechCheck.posClass"),
    )

    is PklCheckPrim.LemmaCheck -> LemmaCheckConfig(property, parseMode<LemmaMode>(mode, "LemmaCheck.mode"), lemmas)

    is PklCheckPrim.GrammaticalShapeCheck -> GrammaticalShapeCheckConfig(
        property = property,
        mode = parseMode<GrammaticalShape>(mode, "GrammaticalShapeCheck.mode"),
    )

    else -> null
}

private fun PklCheckPrim.CompositeCheck.toCompositeConfig(): CompositeCheckConfig = CompositeCheckConfig(
    targetTypes = targetTypes,
    subChecks = subChecks.map { sub ->
        val mapped = CheckConfigMapper.map(sub.config)
        require(mapped is MappedCheck.Deterministic) {
            "CompositeCheck sub-check '${sub.diagnosticCode}' must be deterministic; got ${mapped::class.java.simpleName}"
        }
        SubCheckConfig(diagnosticCode = sub.diagnosticCode, config = mapped.config)
    },
)

private fun PklCheckPrim.LlmCheckRule.toLlmConfig(): LlmCheckRuleConfig = LlmCheckRuleConfig(prompt = prompt, rubric = rubric)

/**
 * Parses [raw] as an enum value of [T], using the enum's declared `name`. Produces an
 * actionable error message naming the [owner] (typically `Primitive.fieldName`) and the
 * expected values when [raw] doesn't match. Used by every Pkl-mode parser so the failure
 * mode is identical across primitives.
 */
private inline fun <reified T : Enum<T>> parseMode(raw: String, owner: String): T = enumValues<T>().firstOrNull { it.name == raw }
    ?: error("Unknown $owner '$raw' (expected one of ${enumValues<T>().joinToString { it.name }})")
