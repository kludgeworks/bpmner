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

import com.fasterxml.jackson.annotation.JsonClassDescription

enum class BpmnDiagnosticSource {
    GRAPH,
    RENDER,
    XSD,
    LINT,
}

enum class BpmnRepairScope {
    LABEL,
    OUTLINE,
    PHASE,
    COMPOSITION,
    LAYOUT,
    FULL_PROCESS,
}

enum class RepairKind {
    LOCAL_MODEL_FIX,
    LOCAL_XML_FIX,
    LLM_MODEL_PATCH,
    LLM_XML_REWRITE,
    UNFIXABLE,
    ;

    fun isLocal(): Boolean = this == LOCAL_MODEL_FIX || this == LOCAL_XML_FIX

    fun isLlm(): Boolean = this == LLM_MODEL_PATCH || this == LLM_XML_REWRITE
}

enum class BpmnRepairSafety { SAFE_AUTOMATIC, SAFE_MANUAL, LLM_ONLY }

@JsonClassDescription(
    "Normalized BPMN validation or rendering diagnostic linked back to the typed definition where possible",
)
data class BpmnDiagnostic(
    val source: BpmnDiagnosticSource,
    val message: String,
    val rule: String? = null,
    val category: String? = null,
    val elementId: String? = null,
    val objectRef: String? = null,
    val repairScope: BpmnRepairScope? = null,
    val ownerRef: String? = null,
    val kind: RepairKind? = null,
    val repairSafety: BpmnRepairSafety? = null,
    val fixHandler: String? = null,
)

data class GlobalDiagnostics(
    val diagnostics: List<BpmnDiagnostic>,
) {
    fun countFor(source: BpmnDiagnosticSource): Int = diagnostics.count { it.source == source }
}

fun BpmnDiagnostic.format(): String =
    buildString {
        append("source=${source.name.lowercase()}")
        rule?.let { append(", rule=$it") }
        category?.let { append(", category=$it") }
        elementId?.let { append(", elementId=$it") }
        objectRef?.let { append(", objectRef=$it") }
        repairScope?.let { append(", repairScope=${it.name.lowercase()}") }
        ownerRef?.let { append(", owner=$it") }
        kind?.let { append(", kind=${it.name.lowercase()}") }
        append(": $message")
    }
