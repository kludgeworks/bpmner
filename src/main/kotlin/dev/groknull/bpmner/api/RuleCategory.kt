/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.api

/**
 * BPMN rule category. Each constant carries:
 *  - [label] — the canonical display name (e.g. `"Activity"`); the string previously stored in
 *    `RuleMetadata.category`. Read directly where a category needs rendering (docs).
 *  - [shortCode] — the rule-id prefix (e.g. `"act"`); `id = "<shortCode>-<slug>"`.
 *
 * The labels and shortCodes mirror `linter/pkl/schema/RuleCategory.pkl` exactly (shortCodes were
 * verified against it — note the irregular ones: `assoc`, `art`, `evt`, `gtw`, `gen`, `msg`).
 * [DEFINITION] is the category of the compiled Kotlin rules in `rules/internal/domain/compiled/`;
 * it has no Pkl counterpart.
 *
 * Kept Jackson/Spring/Embabel-free per [ApiModule] / `ApiAnnotationFreeTest`: a Jackson
 * `@JsonValue` cannot live here, so any serialization that needs the string form reads [label].
 * (`RuleMetadata.category` is not Jackson-serialized anywhere today.)
 */
enum class RuleCategory(val label: String, val shortCode: String) {
    ACTIVITY("Activity", "act"),
    ASSOCIATION("Association", "assoc"),
    ARTIFACT("Artifact", "art"),
    DATA("Data", "data"),
    EVENT("Event", "evt"),
    FLOW("Flow", "flow"),
    GATEWAY("Gateway", "gtw"),
    GENERAL("General", "gen"),
    LANE("Lane", "lane"),
    MESSAGE("Message", "msg"),
    NAME("Name", "name"),
    POOL("Pool", "pool"),
    DEFINITION("Definition", "def"),
    ;

    companion object {
        private val BY_LABEL: Map<String, RuleCategory> = entries.associateBy { it.label }

        /**
         * Resolve a category from its canonical [label] (e.g. the Pkl `RuleCategory.name` value the
         * Pkl bridge reads). Throws on an unknown label — a rule-author error that must fail loudly
         * rather than silently mis-categorise a rule.
         */
        fun fromLabel(label: String): RuleCategory = BY_LABEL[label]
            ?: error("Unknown rule category '$label'. Known labels: ${entries.joinToString { it.label }}")
    }
}
