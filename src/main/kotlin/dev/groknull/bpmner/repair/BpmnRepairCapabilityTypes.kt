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

import dev.groknull.bpmner.validation.BpmnDiagnostic

data class BpmnLocalFixFailure(
    val rule: String,
    val elementId: String?,
    val reason: String,
)

data class BpmnLocalFixSummary(
    val modelApplied: Int,
    val xmlApplied: Int,
    val xmlErrors: Int,
) {
    val total: Int get() = modelApplied + xmlApplied

    companion object {
        val EMPTY = BpmnLocalFixSummary(modelApplied = 0, xmlApplied = 0, xmlErrors = 0)
    }
}

data class BpmnLocalRepairOutcome(
    val failures: List<BpmnLocalFixFailure>,
) {
    fun matches(diagnostic: BpmnDiagnostic): BpmnLocalFixFailure? =
        failures.firstOrNull { it.rule == diagnostic.rule && it.elementId == diagnostic.elementId }

    companion object {
        val EMPTY = BpmnLocalRepairOutcome(emptyList())
    }
}
