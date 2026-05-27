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
     * `*Forms` sets feed the morphological POS tagger so it can recognise bare-infinitive
     * verbs (`Process`, `Send`) that suffix-rules alone would mislabel as nouns.
     */
    internal data class ParsedDict(
        val lemmatizer: DictionaryLemmatizer,
        val verbForms: Set<String>,
        val nounForms: Set<String>,
    )

    companion object {
        /** Each dict line is `form<TAB>POS<TAB>lemma` — exactly 3 tab-separated columns. */
        private const val TSV_COLUMN_COUNT = 3

        /**
         * Loads the bundled lemmatiser dictionary. Strips blank lines and `#`-comment lines
         * before feeding it to OpenNLP's [DictionaryLemmatizer] (which does not tolerate
         * either). Returns the lemmatiser plus per-POS form sets extracted from the same
         * dict — see [ParsedDict].
         */
        internal fun loadDictionary(): ParsedDict {
            val stream = BpmnNlpConfig::class.java.getResourceAsStream(OpenNlpBpmnNlp.DICT_RESOURCE)
                ?: error("Missing classpath resource: ${OpenNlpBpmnNlp.DICT_RESOURCE}")
            val cleanLines = stream.use { input ->
                input.bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() && !it.trimStart().startsWith("#") }.toList()
                }
            }
            val verbForms = mutableSetOf<String>()
            val nounForms = mutableSetOf<String>()
            for (line in cleanLines) {
                val parts = line.split('\t')
                if (parts.size != TSV_COLUMN_COUNT) continue
                val (form, tag, _) = parts
                val lower = form.lowercase()
                when {
                    tag.startsWith("VB") -> verbForms += lower
                    tag.startsWith("NN") -> nounForms += lower
                }
            }
            val joined = cleanLines.joinToString("\n").toByteArray(Charsets.UTF_8)
            val lemmatizer = DictionaryLemmatizer(ByteArrayInputStream(joined))
            return ParsedDict(lemmatizer, verbForms, nounForms)
        }
    }
}
