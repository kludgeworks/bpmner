/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

/**
 * User-facing lint configuration for the modelling team. Loaded once at startup from `bpmner.pkl`
 * (the `ConventionsLoader` lands in #381) into this type, then injected into the rule-definition
 * `@Configuration` classes and the relevant repair handlers.
 *
 * Scaffolding for now (#377): the type and its `bpmner.pkl` template exist; nothing evaluates them
 * yet. The convention word-lists below default to today's Pkl rule literals so behaviour is
 * unchanged out of the box:
 *  - [discouragedLeadingVerbs] — `DiscouragedBusinessVerbs`
 *  - [elementTypeWords] — `NoElementTypeWords` / `NoTypeWordsInDataName` (was `staticConfig.discouragedWords`)
 *  - [allowedAcronyms] — `UncommonAbbreviations` (was `staticConfig.commonAcronyms` / `allowedVocabulary`)
 *
 * Profile selection and per-rule severity overrides move onto this surface in #382.
 */
data class BpmnerLintConfig(
    val discouragedLeadingVerbs: List<String> = listOf("handle", "manage", "process", "perform", "do"),
    val elementTypeWords: List<String> = listOf("activity", "process", "event"),
    val allowedAcronyms: List<String> = listOf("BPMN", "ACME", "SLA", "API", "IT"),
)
