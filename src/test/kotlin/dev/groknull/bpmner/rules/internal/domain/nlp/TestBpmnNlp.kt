/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.nlp

/**
 * Builds a real [OpenNlpBpmnNlp] backed by the bundled lemma dictionary, for use as a
 * shared test fixture. Loaded once at class init so the OpenNLP construction cost is paid
 * once per test class instead of once per `@Test`.
 *
 * Mirrors how [BpmnNlpConfig] builds the production bean — goes through the same
 * comment-stripping loader so test behaviour matches what runtime sees.
 */
internal fun testBpmnNlp(): BpmnNlp {
    val parsed = BpmnNlpConfig.loadDictionary()
    return OpenNlpBpmnNlp(parsed.lemmatizer, parsed.verbForms, parsed.nounForms)
}
