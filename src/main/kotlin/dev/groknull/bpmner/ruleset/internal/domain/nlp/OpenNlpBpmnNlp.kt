/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.ruleset.internal.domain.nlp

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
 *  - POS tagging: stateless lookup in [pennPosTag] over (AUX/WH closed-class word sets ∪
 *    the dictionary-derived form→tag maps), with a suffix-morphology fallback for
 *    out-of-vocabulary tokens. Penn-Treebank tags are returned so they're consumable by
 *    the lemmatiser.
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
 * 2–5 words long — direct dictionary lookup plus suffix-morphology handles them well. A
 * model-backed implementation can replace this class entirely behind the [BpmnNlp]
 * interface without touching any primitive or rule.
 */
internal class OpenNlpBpmnNlp(
    private val lemmatizer: DictionaryLemmatizer,
    /**
     * Lowercased verb forms with their Penn tag (e.g. `sent` → `VBN`).
     * See [dev.groknull.bpmner.ruleset.internal.config.BpmnNlpConfig.ParsedDict].
     */
    private val verbForms: Map<String, String>,
    /**
     * Lowercased noun forms with their Penn tag (e.g. `orders` → `NNS`).
     * See [dev.groknull.bpmner.ruleset.internal.config.BpmnNlpConfig.ParsedDict].
     */
    private val nounForms: Map<String, String>,
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
    private fun coarseTag(penn: String): PosTag = when (penn) {
        "MD" -> PosTag.AUX
        "VBD", "VBN" -> PosTag.VERB_STATE
        in VERB_ACTIVE_PENN -> PosTag.VERB
        in NOUN_PENN -> PosTag.NOUN
        in ADJ_PENN -> PosTag.ADJ
        in WH_PENN -> PosTag.WH
        else -> PosTag.OTHER
    }

    companion object {
        /** Resource path of the hand-curated lemmatiser dictionary. */
        const val DICT_RESOURCE = "/nlp/en-bpmn-lemmas.dict"

        /**
         * Suffix-stripping morphology thresholds. Each represents the minimum length a word
         * must have BEFORE its suffix is trimmed, so that the resulting stem is itself at
         * least two characters long (the practical floor below which a "lemma" stops being
         * a meaningful English root). Named here rather than inlined as integer literals so
         * detekt's MagicNumber rule passes and the cutoffs are documented in one place.
         */
        private const val MIN_LEN_GERUND = 4 // `-ing` → stem ≥ 2 when length > 4
        private const val MIN_LEN_PAST_PARTICIPLE = 3 // `-ed` → stem ≥ 2 when length > 3
        private const val MIN_LEN_PLURAL = 2 // `-s`   → stem ≥ 2 when length > 2
        private const val MIN_LEN_IES_PLURAL = 3 // `-ies` → stem ≥ 1 when length > 3 (with `y` re-added)
        private const val MIN_LEN_COMPARATIVE = 3 // `-er`  → stem ≥ 2 when length > 3
        private const val MIN_LEN_SUPERLATIVE = 3 // `-est` → stem ≥ 2 when length > 3
        private const val MIN_LEN_ADVERB = 2 // `-ly`  → stem ≥ 1 when length > 2

        /** Length thresholds for [morphologicalLemma] — one above the corresponding pos-tag floor. */
        private const val MIN_LEMMA_LEN_GERUND = 5 // `-ing` strip leaves ≥ 2 chars when length > 5
        private const val MIN_LEMMA_LEN_PAST = 4 // `-ed`  strip leaves ≥ 2 chars when length > 4

        // English suffix lengths used by the morphology fallback.
        private const val SUFFIX_LEN_ING = 3
        private const val SUFFIX_LEN_ED = 2
        private const val SUFFIX_LEN_IES = 3
        private const val SUFFIX_LEN_S = 1

        private val AUX_WORDS = setOf(
            "is", "are", "am", "was", "were", "be", "been", "being",
            "has", "have", "had", "having",
            "do", "does", "did", "done", "doing",
            "can", "could", "may", "might", "must", "shall", "should", "will", "would", "ought",
        )

        // Closed-class sets for coarseTag dispatch — small and stable; the same Penn tags are
        // referenced from multiple places so we define them once here.
        private val VERB_ACTIVE_PENN = setOf("VB", "VBP", "VBZ", "VBG")
        private val NOUN_PENN = setOf("NN", "NNS", "NNP", "NNPS")
        private val ADJ_PENN = setOf("JJ", "JJR", "JJS")
        private val WH_PENN = setOf("WP", "WP$", "WDT", "WRB")

        private val WH_PRONOUNS = setOf("what", "who", "whom", "whose", "which")
        private val WH_ADVERBS = setOf("where", "when", "why", "how")

        /**
         * Returns a Penn-Treebank-style POS tag for [token]. Lookup precedence:
         *  1. Auxiliary / WH closed-class word sets (highest specificity).
         *  2. Dictionary maps [verbForms] / [nounForms] — exact form→tag stored at load time,
         *     so irregular forms like `sent` resolve to `VBN` directly without suffix-guessing.
         *  3. Suffix morphology — [pennPosTagByMorphology] handles tokens not in the dict.
         *
         * Designed for short BPMN labels; accuracy on arbitrary English text will be lower.
         */
        internal fun pennPosTag(
            token: String,
            verbForms: Map<String, String>,
            nounForms: Map<String, String>,
        ): String {
            val lower = token.lowercase()
            return pennPosTagForAuxOrWh(lower)
                ?: verbForms[lower]
                ?: nounForms[lower]
                ?: pennPosTagByMorphology(lower)
        }

        /** Closed-class lookup for auxiliary verbs and WH words. Returns `null` if the token isn't one. */
        private fun pennPosTagForAuxOrWh(lower: String): String? {
            if (lower in AUX_WORDS) return pennTagForAux(lower)
            if (lower in WH_PRONOUNS) return "WP"
            if (lower in WH_ADVERBS) return "WRB"
            return null
        }

        /** Sub-classifier for the AUX_WORDS family — maps each form to the Penn tag the lemmatiser expects. */
        private fun pennTagForAux(lower: String): String = when (lower) {
            "is", "has", "does" -> "VBZ"
            "are", "am", "have", "do" -> "VBP"
            "was", "were", "had", "did" -> "VBD"
            "been", "done" -> "VBN"
            "being", "having", "doing" -> "VBG"
            else -> "MD" // modals: can/could/may/might/must/shall/should/will/would/ought
        }

        /**
         * Suffix-only morphology used as the last resort when neither the closed-class sets
         * nor the dictionary recognise the token. Verbal suffixes (`-ing`, `-ed`) are tested
         * first, then nominal/adjectival/adverbial suffixes. Each predicate-helper drops
         * one decision off this dispatcher so it stays under detekt's complexity threshold.
         */
        private fun pennPosTagByMorphology(lower: String): String = verbalMorphology(lower)
            ?: nominalMorphology(lower)
            ?: "NN"

        /** Recognises gerund (`-ing`) and past-participle (`-ed`) suffixes. */
        private fun verbalMorphology(lower: String): String? = when {
            lower.endsWith("ing") && lower.length > MIN_LEN_GERUND -> "VBG"
            lower.endsWith("ed") && lower.length > MIN_LEN_PAST_PARTICIPLE -> "VBN"
            else -> null
        }

        /** Recognises plural / comparative / superlative / adverb suffixes. */
        private fun nominalMorphology(lower: String): String? = when {
            lower.endsWith("ies") && lower.length > MIN_LEN_IES_PLURAL -> "NNS"
            lower.endsWith("ly") && lower.length > MIN_LEN_ADVERB -> "RB"
            lower.endsWith("er") && lower.length > MIN_LEN_COMPARATIVE -> "JJR"
            lower.endsWith("est") && lower.length > MIN_LEN_SUPERLATIVE -> "JJS"
            lower.endsWith("s") && !lower.endsWith("ss") && lower.length > MIN_LEN_PLURAL -> "NNS"
            else -> null
        }

        /**
         * Morphological fallback for tokens the dictionary lemmatiser returns `"O"` for.
         * Trims `-ing`, `-ed`, `-s`, `-ies` and returns the stem (lowercased). Conservative:
         * when the morphology is ambiguous, returns the lowercased token unchanged.
         */
        internal fun morphologicalLemma(token: String, pennTag: String): String {
            val lower = token.lowercase()
            return when {
                pennTag.startsWith("VB") && lower.endsWith("ing") && lower.length > MIN_LEMMA_LEN_GERUND ->
                    lower.dropLast(SUFFIX_LEN_ING)

                pennTag.startsWith("VB") && lower.endsWith("ed") && lower.length > MIN_LEMMA_LEN_PAST ->
                    lower.dropLast(SUFFIX_LEN_ED)

                pennTag == "NNS" && lower.endsWith("ies") && lower.length > MIN_LEMMA_LEN_PAST ->
                    lower.dropLast(SUFFIX_LEN_IES) + "y"

                pennTag == "NNS" && lower.endsWith("s") && lower.length > MIN_LEN_PLURAL ->
                    lower.dropLast(SUFFIX_LEN_S)

                else -> lower
            }
        }
    }
}
