/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.nlp

import opennlp.tools.lemmatizer.DictionaryLemmatizer
import opennlp.tools.tokenize.SimpleTokenizer

/**
 * OpenNLP-backed [BpmnNlp] implementation.
 *
 * **Components**:
 *  - Tokenisation: `SimpleTokenizer.INSTANCE` — stateless OpenNLP utility, no model file.
 *  - Lemmatisation: OpenNLP `DictionaryLemmatizer` over [DICT_RESOURCE], a hand-curated
 *    BPMN-domain TSV. Unknown forms (the lemmatiser returns `"O"`) fall back to the
 *    [morphologicalLemma] heuristic.
 *  - POS tagging: stateless morphological heuristic in [pennPosTag] — recognises modal
 *    auxiliaries, WH-words, and the `-ing` / `-ed` / `-s` / `-ly` suffix families. Produces
 *    Penn-Treebank-style tags so they're directly consumable by the lemmatiser.
 *
 * **Thread safety**. All three OpenNLP objects used here are read-only after construction:
 *  - `SimpleTokenizer.INSTANCE` is a stateless singleton.
 *  - `DictionaryLemmatizer` only reads from its loaded map.
 *  - The morphological POS tagger is pure.
 *
 * The [BpmnNlpConcurrencyTest] regression-guards this. If a future maintainer swaps in a
 * model-backed `POSTaggerME` (which is NOT thread-safe), it must instantiate one per call
 * inside the methods below — see the warning in [BpmnNlp]'s KDoc.
 *
 * **Why a morphological POS tagger rather than `POSTaggerME`?** Apache OpenNLP 2.x stopped
 * shipping pretrained POSModels on a stable Maven coordinate, and BPMN labels are typically
 * 2–5 words long — morphology + a small curated AUX/WH/verb dictionary handles them well.
 * A model-backed implementation can replace this class entirely behind the [BpmnNlp]
 * interface without touching any primitive or rule.
 */
internal class OpenNlpBpmnNlp(
    private val lemmatizer: DictionaryLemmatizer,
    /** Lowercased forms of known verbs, extracted from the lemmatiser dictionary. */
    private val verbForms: Set<String>,
    /** Lowercased forms of known nouns, extracted from the lemmatiser dictionary. */
    private val nounForms: Set<String>,
) : BpmnNlp {
    override fun tokens(text: String): List<String> = if (text.isBlank()) {
        emptyList()
    } else {
        SimpleTokenizer.INSTANCE.tokenize(text)
            .filter { it.any(Char::isLetterOrDigit) }
    }

    override fun posTags(text: String): List<PosTag> = tokens(text).map { token ->
        // AUX_WORDS includes both modals (MD → AUX via coarseTag) and copular/perfect/do-support
        // forms tagged VBZ/VBP/VBD/VBN/VBG. The latter would otherwise surface as VERB; we
        // override here so rules like DivergingGatewayQuestion ("Is the order valid?") see AUX.
        val penn = pennPosTag(token, verbForms, nounForms)
        if (token.lowercase() in AUX_WORDS && penn != "MD") PosTag.AUX else coarseTag(penn)
    }

    override fun lemma(token: String): String {
        val pennTag = pennPosTag(token, verbForms, nounForms)
        val lemma = lemmatizer.lemmatize(arrayOf(token.lowercase()), arrayOf(pennTag)).single()
        return if (lemma == "O") morphologicalLemma(token, pennTag) else lemma
    }

    override fun lemmasOf(text: String): List<String> {
        val tokens = tokens(text)
        if (tokens.isEmpty()) return emptyList()
        val lower = tokens.map { it.lowercase() }
        val penn = tokens.map { pennPosTag(it, verbForms, nounForms) }
        val raw = lemmatizer.lemmatize(lower.toTypedArray(), penn.toTypedArray())
        return raw.mapIndexed { i, l -> if (l == "O") morphologicalLemma(tokens[i], penn[i]) else l }
    }

    /** Maps a Penn-Treebank-style tag to the coarse [PosTag] surface. AUX override happens upstream in [posTags]. */
    private fun coarseTag(penn: String): PosTag = when {
        penn == "MD" -> PosTag.AUX
        penn.startsWith("VB") -> PosTag.VERB
        penn.startsWith("NN") -> PosTag.NOUN
        penn.startsWith("JJ") -> PosTag.ADJ
        penn.startsWith("W") -> PosTag.WH
        else -> PosTag.OTHER
    }

    companion object {
        /** Resource path of the hand-curated lemmatiser dictionary. */
        const val DICT_RESOURCE = "/nlp/en-bpmn-lemmas.dict"

        private val AUX_WORDS = setOf(
            "is", "are", "am", "was", "were", "be", "been", "being",
            "has", "have", "had", "having",
            "do", "does", "did", "done", "doing",
            "can", "could", "may", "might", "must", "shall", "should", "will", "would", "ought",
        )

        private val WH_PRONOUNS = setOf("what", "who", "whom", "whose", "which")
        private val WH_ADVERBS = setOf("where", "when", "why", "how")

        /**
         * Returns a Penn-Treebank-style POS tag for [token] using morphological rules plus
         * three dictionary-backed lookups (AUX/WH word lists, plus the verb/noun form sets
         * extracted from the lemmatiser dict). Designed for short BPMN labels — accuracy on
         * arbitrary English text will be much lower.
         *
         * Lookup precedence:
         *  1. AUX_WORDS / WH lists (highest specificity)
         *  2. [verbForms] / [nounForms] — known forms from the BPMN lemma dict, with Penn
         *     tag refined by morphology (`-ed` → VBN, `-ing` → VBG, `-s` → VBZ or NNS, …)
         *  3. Pure-morphology suffix rules (`-ing` → VBG, etc.)
         *  4. Default NN
         */
        @Suppress(
            "ReturnCount", // tag-resolution funnel — each branch is an early return for clarity
            "CyclomaticComplexMethod", // intentional flat `when`-cascade over POS tag families
            "MagicNumber", // length thresholds are morphological cutoffs, not policy values
        )
        internal fun pennPosTag(token: String, verbForms: Set<String>, nounForms: Set<String>): String {
            val lower = token.lowercase()
            if (lower in AUX_WORDS) {
                return when (lower) {
                    "is", "has", "does" -> "VBZ"
                    "are", "am", "have", "do" -> "VBP"
                    "was", "were", "had", "did" -> "VBD"
                    "been", "done" -> "VBN"
                    "being", "having", "doing" -> "VBG"
                    else -> "MD" // modal: can/could/may/might/must/shall/should/will/would/ought
                }
            }
            if (lower in WH_PRONOUNS) return "WP"
            if (lower in WH_ADVERBS) return "WRB"
            // Dictionary-backed verb detection. We pick the Penn sub-tag by suffix so
            // lemmatisation downstream gets the right form-tag pair.
            if (lower in verbForms) {
                return when {
                    lower.endsWith("ing") && lower.length > 4 -> "VBG"
                    lower.endsWith("ed") && lower.length > 3 -> "VBN"
                    lower.endsWith("s") && !lower.endsWith("ss") && lower.length > 2 -> "VBZ"
                    else -> "VB"
                }
            }
            if (lower in nounForms) {
                return if (lower.endsWith("s") && !lower.endsWith("ss") && lower.length > 2) "NNS" else "NN"
            }
            // Pure-morphology fallback. Longer suffixes first so `-ies` outranks `-s`.
            return when {
                lower.length > 4 && lower.endsWith("ing") -> "VBG"
                lower.length > 3 && lower.endsWith("ed") -> "VBN"
                lower.length > 3 && lower.endsWith("ies") -> "NNS"
                lower.length > 2 && lower.endsWith("ly") -> "RB"
                lower.length > 3 && lower.endsWith("er") -> "JJR"
                lower.length > 3 && lower.endsWith("est") -> "JJS"
                lower.length > 1 && lower.endsWith("s") && !lower.endsWith("ss") -> "NNS"
                else -> "NN"
            }
        }

        /**
         * Morphological fallback for tokens the dictionary lemmatiser doesn't know. Trims
         * `-ing`, `-ed`, `-s`, `-ies` and returns the stem (lowercased). Conservative — when
         * the morphology is ambiguous (e.g. `-ed` could be past-tense or past-participle of
         * a verb but also a denominal adjective), returns the lowercased token unchanged.
         */
        @Suppress("MagicNumber") // suffix-strip lengths are morphological cutoffs, not policy values
        internal fun morphologicalLemma(token: String, pennTag: String): String {
            val lower = token.lowercase()
            return when {
                pennTag.startsWith("VB") && lower.endsWith("ing") && lower.length > 5 -> lower.dropLast(3)
                pennTag.startsWith("VB") && lower.endsWith("ed") && lower.length > 4 -> lower.dropLast(2)
                pennTag == "NNS" && lower.endsWith("ies") && lower.length > 4 -> lower.dropLast(3) + "y"
                pennTag == "NNS" && lower.endsWith("s") && lower.length > 2 -> lower.dropLast(1)
                else -> lower
            }
        }
    }
}
