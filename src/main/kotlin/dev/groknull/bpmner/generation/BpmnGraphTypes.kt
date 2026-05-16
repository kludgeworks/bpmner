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

package dev.groknull.bpmner.generation

import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnRequest
import dev.groknull.bpmner.validation.BpmnDiagnostic
import jakarta.validation.Valid

// --- Outline and phase types (intermediate stages within generation) ---

data class ProcessOutline(
    val request: BpmnRequest,
    @field:Valid
    val definition: BpmnDefinition,
    @field:Valid
    val metrics: OutlineMetrics,
)

data class OutlineMetrics(
    val phaseCount: Int,
    val branchCount: Int,
    val loopCount: Int,
    val subprocessCount: Int,
)

data class ValidatedOutline(
    val outline: ProcessOutline,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
) {
    val definition: BpmnDefinition
        get() = outline.definition
}

data class PhasePlan(
    val phaseId: String,
    val ownerRef: String,
    @field:Valid
    val definition: BpmnDefinition,
)

data class PhasePlanSet(
    val outline: ValidatedOutline,
    val phasePlans: List<PhasePlan>,
)

data class ValidatedPhasePlan(
    val phaseId: String,
    val ownerRef: String,
    @field:Valid
    val definition: BpmnDefinition,
    val diagnostics: List<BpmnDiagnostic> = emptyList(),
)

data class ValidatedPhasePlanSet(
    val outline: ValidatedOutline,
    val phasePlans: List<ValidatedPhasePlan>,
) {
    val definition: BpmnDefinition
        get() = phasePlans.single().definition
}
