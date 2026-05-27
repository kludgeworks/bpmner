/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.nlp

/**
 * Coarse part-of-speech classes used by the NLP-aware check primitives.
 *
 * Mapped from finer-grained Penn-Treebank-style tags inside [BpmnNlp]. The seven NLP-
 * dependent rules (#218 / #196) only distinguish between verb, noun, adjective, modal
 * auxiliary, interrogative (WH-word), and "anything else" — exposing Penn tags would leak
 * a vendor-specific tag set into every rule's Pkl config.
 */
enum class PosTag {
    /** Action verbs in any tense, including bare infinitives, gerunds, and past participles. */
    VERB,

    /** Common and proper nouns, singular or plural. */
    NOUN,

    /** Adjectives. */
    ADJ,

    /** Modal / auxiliary verbs (`is`, `are`, `can`, `will`, `should`, …) — leading-auxiliary tests use this. */
    AUX,

    /** Interrogative words (`what`, `who`, `why`, `where`, …). */
    WH,

    /**
     * Unrecognised or out-of-vocabulary — primitives should treat conservatively (no match
     * for REQUIRE, no match for FORBID).
     */
    OTHER,
}

/**
 * Façade for the natural-language operations the BPMN rule engine needs.
 *
 * Kept deliberately small — only the four operations the seven Phase-3 NLP rules actually
 * use. Adding new methods is cheap, but each addition is a coupling point between the rule
 * authors and the NLP vendor, so prefer composing existing methods at the primitive layer.
 *
 * **Threading.** Implementations MUST be safe to call concurrently from multiple rule-engine
 * threads. The default [OpenNlpBpmnNlp] satisfies this because it uses only OpenNLP
 * components that are thread-safe by construction (`SimpleTokenizer.INSTANCE`,
 * `DictionaryLemmatizer` with a read-only dictionary) plus a stateless morphological POS
 * tagger. If a future implementation swaps in `POSTaggerME` (which is NOT thread-safe), it
 * must instantiate one per call inside its methods — see [BpmnNlpConcurrencyTest] for the
 * regression check.
 *
 * **Why no model files for now.** Apache OpenNLP 2.x stopped shipping pretrained models on
 * a stable Maven coordinate, and BPMN labels are short (≤ 5 words on average) — a
 * morphological POS tagger handles them well enough for the seven activated rules. When a
 * model-backed implementation is needed, it can replace [OpenNlpBpmnNlp] behind this
 * interface without touching any primitive.
 */
interface BpmnNlp {
    /** Tokenises [text] into whitespace-separated words, dropping punctuation. */
    fun tokens(text: String): List<String>

    /** Assigns one [PosTag] per token returned by [tokens]. The list is the same length as [tokens]`(text)`. */
    fun posTags(text: String): List<PosTag>

    /** Returns the lemma (dictionary base form) of [token]. Falls back to a lowercased copy of [token] if unknown. */
    fun lemma(token: String): String

    /** Convenience: [tokens] followed by per-token [lemma]. The list is the same length as [tokens]`(text)`. */
    fun lemmasOf(text: String): List<String>
}
