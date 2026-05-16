/*
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
