/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.validation

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

enum class BpmnLintPhase {
    SEMANTIC_PRE_LAYOUT,
    FINAL_POST_LAYOUT,
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LintIssue(
    val id: String?,
    val rule: String,
    val message: String,
    val category: String = "error",
    @JsonIgnore
    val rawFields: MutableMap<String, Any?> = mutableMapOf(),
) {
    @JsonAnySetter
    fun add(
        name: String,
        value: Any?,
    ) {
        if (name !in KNOWN_FIELDS) rawFields[name] = value
    }

    companion object {
        private val KNOWN_FIELDS = setOf("id", "rule", "message", "category")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BpmnAutoFixResult(
    val changed: Boolean = false,
    val xml: String = "",
    val applied: List<BpmnAutoFixChange> = emptyList(),
    val skipped: List<BpmnAutoFixSkip> = emptyList(),
    val errors: List<BpmnAutoFixError> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BpmnAutoFixChange(
    val rule: String,
    val elementId: String? = null,
    val message: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BpmnAutoFixSkip(
    val rule: String,
    val elementId: String? = null,
    val message: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BpmnAutoFixError(
    val rule: String,
    val elementId: String? = null,
    val message: String,
)

data class XsdValidationIssue(
    val message: String,
    val elementId: String? = null,
)
