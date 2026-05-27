/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.nlp

import opennlp.tools.lemmatizer.DictionaryLemmatizer
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream

/**
 * Builds the application-wide [BpmnNlp] singleton from the bundled BPMN-domain lemmatiser
 * dictionary. Loaded once at startup and shared across all rule-engine threads — see
 * [OpenNlpBpmnNlp]'s KDoc for the thread-safety argument.
 *
 * Mirrors the [dev.groknull.bpmner.rules.internal.domain.RuleProfileFactory] pattern
 * introduced in Phase 2E: a small `@Configuration` whose only job is to compose Spring DI
 * with a stateful resource the engine needs early.
 */
@Configuration
internal class BpmnNlpConfig {
    private val logger = LoggerFactory.getLogger(BpmnNlpConfig::class.java)

    @Bean
    fun bpmnNlp(): BpmnNlp {
        val parsed = loadDictionary()
        logger.info(
            "BpmnNlp loaded {} verb forms, {} noun forms from {}",
            parsed.verbForms.size,
            parsed.nounForms.size,
            OpenNlpBpmnNlp.DICT_RESOURCE,
        )
        return OpenNlpBpmnNlp(parsed.lemmatizer, parsed.verbForms, parsed.nounForms)
    }

    /**
     * Parsed view of the lemmatiser dictionary — used by both the production bean and the
     * test fixture so they share one loading path. [lemmatizer] handles `lemma()`; the
     * `*Forms` maps feed the POS tagger with the precise Penn-Treebank tag for each known
     * form (e.g. `sent → VBN`), which a suffix-only heuristic cannot recover for irregulars.
     */
    internal data class ParsedDict(
        val lemmatizer: DictionaryLemmatizer,
        val verbForms: Map<String, String>,
        val nounForms: Map<String, String>,
    )

    companion object {
        /** Each dict line is `form<TAB>POS<TAB>lemma` — exactly 3 tab-separated columns. */
        private const val TSV_COLUMN_COUNT = 3

        /**
         * Priority for resolving form ambiguity when a single token has multiple Penn tags
         * in the dictionary. Lower index = higher priority. The ordering reflects what the
         * NLP-aware rules need:
         *
         *  - past-participle (`VBN`) wins for state-label rules — `Order sent` is a state.
         *  - bare infinitive (`VB`) wins over noun (`NN`) on Verb+Object activity names —
         *    `Process` in `Process the order` should be tagged VERB even though `process` is
         *    also a noun.
         *  - VBG (gerund), VBD (past tense), VBZ/VBP (present) sit between VBN and VB.
         *
         * Tags not in this table land at the bottom (treated as ties; last-write wins).
         */
        private val PENN_TAG_PRIORITY: List<String> = listOf(
            "VBN",
            "VBG",
            "VBD",
            "VBZ",
            "VBP",
            "VB",
            "NN",
            "NNS",
        )

        /**
         * Loads the bundled lemmatiser dictionary. Filters blank lines, `#`-comment lines,
         * and any malformed TSV (≠ 3 tab-separated columns) once — the surviving lines feed
         * BOTH the [DictionaryLemmatizer] (which crashes on malformed input) AND the
         * per-POS form maps. Returns the parsed view as [ParsedDict].
         */
        internal fun loadDictionary(): ParsedDict {
            val stream = BpmnNlpConfig::class.java.getResourceAsStream(OpenNlpBpmnNlp.DICT_RESOURCE)
                ?: error("Missing classpath resource: ${OpenNlpBpmnNlp.DICT_RESOURCE}")
            val validLines = stream.use { input ->
                input.bufferedReader().useLines { lines ->
                    lines
                        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                        .filter { it.split('\t').size == TSV_COLUMN_COUNT }
                        .toList()
                }
            }
            val verbForms = mutableMapOf<String, String>()
            val nounForms = mutableMapOf<String, String>()
            for (line in validLines) {
                val (form, tag, _) = line.split('\t')
                val lower = form.lowercase()
                when {
                    tag.startsWith("VB") -> verbForms[lower] = preferTag(verbForms[lower], tag)
                    tag.startsWith("NN") -> nounForms[lower] = preferTag(nounForms[lower], tag)
                }
            }
            val joined = validLines.joinToString("\n").toByteArray(Charsets.UTF_8)
            val lemmatizer = DictionaryLemmatizer(ByteArrayInputStream(joined))
            return ParsedDict(lemmatizer, verbForms, nounForms)
        }

        /**
         * When the same lowercased form appears with multiple Penn tags in the dictionary
         * (e.g. `sent VBD send` + `sent VBN send`), pick the higher-priority one per
         * [PENN_TAG_PRIORITY]. Tags absent from the priority list are treated as last and
         * lose ties to known tags — they only win if no known tag has been seen yet.
         */
        private fun preferTag(existing: String?, incoming: String): String {
            if (existing == null) return incoming
            val existingRank = PENN_TAG_PRIORITY.indexOf(existing).let { if (it == -1) Int.MAX_VALUE else it }
            val incomingRank = PENN_TAG_PRIORITY.indexOf(incoming).let { if (it == -1) Int.MAX_VALUE else it }
            return if (incomingRank < existingRank) incoming else existing
        }
    }
}
