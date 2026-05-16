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

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.RenderedBpmn
import dev.groknull.bpmner.validation.ValidatedBpmnXml

data class BpmnEvaluation(
    val definition: BpmnDefinition,
    val rendered: RenderedBpmn?,
    val diagnostics: List<BpmnDiagnostic>,
    val globalDiagnostics: GlobalDiagnostics,
    val validatedXml: String?,
    val renderFailureMessage: String? = null,
    val rawLintIssues: List<LintIssue>? = null,
) {
    fun isSuccessful(): Boolean = validatedXml != null && diagnostics.isEmpty()

    fun toValidatedBpmnXml(repairAttempts: Int): ValidatedBpmnXml =
        ValidatedBpmnXml(
            definition = definition,
            xml = validatedXml ?: error("No validated BPMN XML available"),
            diagnostics = diagnostics,
            repairAttempts = repairAttempts,
        )
}

class BpmnValidatorInfrastructureException(
    message: String,
) : IllegalStateException(message)
