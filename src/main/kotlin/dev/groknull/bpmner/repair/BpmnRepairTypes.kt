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

package dev.groknull.bpmner.repair

import com.embabel.chat.Message
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.LaidOutProcessGraph
import dev.groknull.bpmner.validation.BpmnDiagnostic
import dev.groknull.bpmner.validation.BpmnEvaluation

data class BpmnRepairAttempt(
    val attemptNumber: Int,
    val repairAttempts: Int,
    val graph: LaidOutProcessGraph,
    val evaluation: BpmnEvaluation,
    val messages: List<Message>,
) {
    val definition: BpmnDefinition
        get() = evaluation.definition

    val diagnostics: List<BpmnDiagnostic>
        get() = evaluation.diagnostics
}

data class BpmnAttemptHistory(
    val records: List<BpmnAttemptRecord> = emptyList(),
) {
    val size: Int get() = records.size
    val last: BpmnAttemptRecord? get() = records.lastOrNull()

    fun append(record: BpmnAttemptRecord): BpmnAttemptHistory = copy(records = records + record)

    fun containsDefinitionFingerprint(fingerprint: String): Boolean = records.any { it.definitionFingerprint == fingerprint }

    fun compact(): String = records.joinToString(" | ") { it.compact() }
}

data class BpmnAttemptRecord(
    val attemptNumber: Int,
    val repairAttempts: Int,
    val graphDiagnostics: Int,
    val renderDiagnostics: Int,
    val xsdDiagnostics: Int,
    val lintDiagnostics: Int,
    val diagnosticFingerprint: String,
    val definitionFingerprint: String,
    val repairPromptFingerprint: String?,
    val topDiagnostics: List<String> = emptyList(),
) {
    fun compact(): String =
        "#$attemptNumber(repairs=$repairAttempts,graph=$graphDiagnostics,render=$renderDiagnostics," +
            "xsd=$xsdDiagnostics,lint=$lintDiagnostics,diag=$diagnosticFingerprint," +
            "def=$definitionFingerprint,prompt=${repairPromptFingerprint ?: "-"})"
}

class BpmnRefinementFailureException(
    message: String,
    val summary: String,
) : IllegalStateException(message)
