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

package dev.groknull.bpmner.alignment

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import dev.groknull.bpmner.contract.TraceLink
import dev.groknull.bpmner.core.AlignmentClassification
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

enum class AlignmentVerdict {
    ALIGNED,
    PARTIALLY_ALIGNED,
    FAILED,
}

@JsonClassDescription("Summary of a generated BPMN definition for alignment checking")
data class BpmnDefinitionSummary(
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN process id")
    val processId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN process name")
    val processName: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Summary of generated BPMN elements")
    val elements: List<BpmnSummaryElement>,
    @field:Valid
    @get:JsonPropertyDescription("Summary of generated BPMN sequence flows")
    val flows: List<BpmnSummaryFlow> = emptyList(),
    @get:JsonPropertyDescription("IDs of elements that are unreachable from start events")
    val unreachableElementIds: List<String> = emptyList(),
)

@JsonClassDescription("Summary of a single generated BPMN element")
data class BpmnSummaryElement(
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN element id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN element type")
    val type: String,
    @get:JsonPropertyDescription("Optional BPMN element name")
    val name: String? = null,
)

@JsonClassDescription("Summary of a single generated BPMN sequence flow")
data class BpmnSummaryFlow(
    @field:NotBlank
    @get:JsonPropertyDescription("BPMN sequence flow id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source BPMN element id")
    val sourceRef: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target BPMN element id")
    val targetRef: String,
    @get:JsonPropertyDescription("Optional sequence flow name")
    val name: String? = null,
    @get:JsonPropertyDescription("Optional sequence flow condition expression")
    val conditionExpression: String? = null,
)

@JsonClassDescription("Alignment report comparing the process contract with generated BPMN")
data class BpmnAlignmentReport(
    @get:JsonPropertyDescription("Overall alignment verdict")
    val verdict: AlignmentVerdict,
    @field:Valid
    @get:JsonPropertyDescription("Generated BPMN summary used for alignment")
    val bpmnSummary: BpmnDefinitionSummary,
    @field:Valid
    @get:JsonPropertyDescription("Aligned BPMN and contract elements")
    val alignedElements: List<AlignedElement> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Trace links supporting the alignment report")
    val traceLinks: List<TraceLink> = emptyList(),
    @field:NotBlank
    @get:JsonPropertyDescription("Short explanation for the alignment verdict")
    val rationale: String,
)

@JsonClassDescription("Alignment result for a generated BPMN element or missing contract element")
data class AlignedElement(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable aligned-element id")
    val id: String,
    @get:JsonPropertyDescription("Optional contract element id")
    val contractElementId: String? = null,
    @get:JsonPropertyDescription("Optional BPMN element id")
    val bpmnElementId: String? = null,
    @get:JsonPropertyDescription("Alignment classification for this element")
    val classification: AlignmentClassification,
    @field:NotBlank
    @get:JsonPropertyDescription("Explanation of this element's alignment")
    val rationale: String,
    @field:Valid
    @get:JsonPropertyDescription("Trace links supporting this element alignment")
    val traceLinks: List<TraceLink> = emptyList(),
)

class BpmnAlignmentException(
    message: String,
    val report: BpmnAlignmentReport,
) : RuntimeException(message)
