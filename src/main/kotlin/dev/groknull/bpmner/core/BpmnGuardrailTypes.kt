package dev.groknull.bpmner.core

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

enum class GenerationMode {
    SINGLE_SHOT,
    INTERACTIVE,
}

enum class BpmnGenerationStatus {
    GENERATED,
    NEEDS_CLARIFICATION,
    NOT_A_PROCESS,
    ALIGNMENT_FAILED,
    VALIDATION_FAILED,
}

enum class ReadinessVerdict {
    READY,
    NEEDS_CLARIFICATION,
    NOT_A_PROCESS,
}

enum class ReadinessDimension {
    PROCESS_BOUNDARY,
    START_TRIGGER,
    END_STATES,
    ACTIVITIES,
    SEQUENCE_ORDER,
    ACTORS_ROLES,
    DECISIONS_BRANCHES,
    EXCEPTIONS_REWORK,
    INPUTS_OUTPUTS_ARTIFACTS,
    SCOPE_CLARITY,
    BPMN_SUITABILITY,
    TRACEABILITY_TO_SOURCE,
}

enum class MissingProcessArea {
    PROCESS_BOUNDARY,
    START_TRIGGER,
    END_STATE,
    ACTIVITY_SEQUENCE,
    ACTOR_RESPONSIBILITY,
    DECISION_CRITERIA,
    EXCEPTION_HANDLING,
    INPUT_ARTIFACT,
    OUTPUT_ARTIFACT,
    BPMN_PROCESS_SUITABILITY,
    SOURCE_TRACE,
}

enum class AlignmentVerdict {
    ALIGNED,
    PARTIALLY_ALIGNED,
    FAILED,
}

enum class AlignmentClassification {
    SUPPORTED,
    ASSUMED,
    UNSUPPORTED,
    COVERED,
    PARTIALLY_COVERED,
    MISSING,
    EXTRA,
    CONTRADICTED,
}

enum class EvidenceSourceType {
    ORIGINAL_INPUT,
    STYLE_GUIDE,
    CLARIFICATION,
    GENERATED_BPMN,
}

@JsonClassDescription("Source-grounded BPMN generation context with ordered clarification history")
data class BpmnGenerationContext(
    @field:NotBlank
    @get:JsonPropertyDescription("Original natural-language input text provided for BPMN generation")
    val originalInputText: String,
    @get:JsonPropertyDescription("Optional style guide text that constrains naming and structure")
    val styleGuide: String? = null,
    @field:NotBlank
    @get:JsonPropertyDescription("Requested BPMN output file path")
    val outputFile: String,
    @get:JsonPropertyDescription("Generation mode requested by the inbound adapter")
    val mode: GenerationMode = GenerationMode.SINGLE_SHOT,
    @field:Valid
    @get:JsonPropertyDescription("Ordered answered clarification history for this generation context")
    val clarificationHistory: List<ClarificationExchange> = emptyList(),
)

@JsonClassDescription("Readiness assessment of whether source input is sufficient to generate BPMN")
data class ProcessInputAssessment(
    @get:JsonPropertyDescription("Readiness verdict for the supplied process input")
    val verdict: ReadinessVerdict,
    @field:Min(0)
    @get:JsonPropertyDescription("Overall readiness score, where higher means more generation-ready")
    val overallScore: Int,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Dimension-level readiness scores supporting the verdict")
    val dimensions: List<ReadinessDimensionScore>,
    @get:JsonPropertyDescription("Process areas that are missing or underspecified")
    val missingAreas: List<MissingProcessArea> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Clarification questions proposed for unresolved missing areas")
    val clarificationQuestions: List<ClarificationQuestion> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Source evidence used to justify the assessment")
    val evidence: List<SourceEvidence> = emptyList(),
    @field:NotBlank
    @get:JsonPropertyDescription("Short explanation for the readiness verdict")
    val rationale: String,
)

@JsonClassDescription("Score for a single readiness dimension")
data class ReadinessDimensionScore(
    @get:JsonPropertyDescription("Readiness dimension being scored")
    val dimension: ReadinessDimension,
    @field:Min(0)
    @get:JsonPropertyDescription("Score for this dimension, where higher means more complete")
    val score: Int,
    @field:NotBlank
    @get:JsonPropertyDescription("Explanation of the score")
    val rationale: String,
    @get:JsonPropertyDescription("Missing process areas related to this dimension")
    val missingAreas: List<MissingProcessArea> = emptyList(),
)

@JsonClassDescription("Clarification question proposed by the readiness model")
data class ClarificationQuestion(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable question id for correlating later answers")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Question text to ask the user")
    val questionText: String,
    @get:JsonPropertyDescription("Missing process areas this question is intended to resolve")
    val relatedMissingAreas: List<MissingProcessArea> = emptyList(),
    @get:JsonPropertyDescription("Readiness dimensions this question is intended to improve")
    val relatedDimensions: List<ReadinessDimension> = emptyList(),
    @field:Size(max = 8)
    @get:JsonPropertyDescription("Optional bounded answer options when the question is multiple choice")
    val options: List<String> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Evidence motivating the question")
    val evidence: List<SourceEvidence> = emptyList(),
)

@JsonClassDescription("Source evidence supporting a guardrail assessment or trace")
data class SourceEvidence(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable evidence id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Relevant source text excerpt or concise paraphrase")
    val text: String,
    @get:JsonPropertyDescription("Type of source the evidence came from")
    val sourceType: EvidenceSourceType,
    @get:JsonPropertyDescription("Optional source reference, such as a filename or clarification question id")
    val sourceRef: String? = null,
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Optional inclusive start offset in the source text")
    val startOffset: Int? = null,
    @field:PositiveOrZero
    @get:JsonPropertyDescription("Optional exclusive end offset in the source text")
    val endOffset: Int? = null,
)

@JsonClassDescription("Answered clarification history item")
data class ClarificationExchange(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable id of the clarification question that was answered")
    val questionId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Question text that was asked")
    val questionText: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Answer text supplied by the user")
    val answerText: String,
    @get:JsonPropertyDescription("Missing process areas resolved or affected by this exchange")
    val relatedMissingAreas: List<MissingProcessArea> = emptyList(),
    @get:JsonPropertyDescription("Readiness dimensions resolved or affected by this exchange")
    val relatedDimensions: List<ReadinessDimension> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Evidence or trace metadata attached to this answered exchange")
    val evidence: List<SourceEvidence> = emptyList(),
)

@JsonClassDescription("Source-grounded process contract extracted before BPMN generation")
data class ProcessContract(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable contract id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Human-readable process name")
    val processName: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Concise process summary")
    val summary: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Process start trigger derived from the source input")
    val trigger: String,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding the trigger in source evidence")
    val triggerTraceLinks: List<TraceLink> = emptyList(),
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Activities required by the process contract")
    val activities: List<ContractActivity>,
    @field:Valid
    @get:JsonPropertyDescription("Decisions and branch points required by the process contract")
    val decisions: List<ContractDecision> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Actors referenced by the process contract")
    val actors: List<ContractActor> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Artifacts referenced by the process contract")
    val artifacts: List<ContractArtifact> = emptyList(),
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Required process end states")
    val endStates: List<ContractEndState>,
    @field:Valid
    @get:JsonPropertyDescription("Assumptions made while extracting the contract")
    val assumptions: List<ContractAssumption> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Trace links from contract elements to source evidence")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Activity required by the extracted process contract")
data class ContractActivity(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable activity id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Business activity name")
    val name: String,
    @get:JsonPropertyDescription("Optional actor id responsible for the activity")
    val actorId: String? = null,
    @get:JsonPropertyDescription("Artifact ids consumed by the activity")
    val inputArtifactIds: List<String> = emptyList(),
    @get:JsonPropertyDescription("Artifact ids produced by the activity")
    val outputArtifactIds: List<String> = emptyList(),
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this activity")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Decision required by the extracted process contract")
data class ContractDecision(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable decision id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Business decision question")
    val question: String,
    @field:NotEmpty
    @field:Valid
    @get:JsonPropertyDescription("Branches that can be taken from this decision")
    val branches: List<ContractBranch>,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this decision")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Branch from a contract decision")
data class ContractBranch(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable branch id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Branch label")
    val label: String,
    @get:JsonPropertyDescription("Optional condition expression that selects this branch")
    val condition: String? = null,
)

@JsonClassDescription("Actor referenced by the extracted process contract")
data class ContractActor(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable actor id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Actor name")
    val name: String,
    @get:JsonPropertyDescription("Optional actor role or responsibility")
    val role: String? = null,
)

@JsonClassDescription("Artifact referenced by the extracted process contract")
data class ContractArtifact(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable artifact id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Artifact name")
    val name: String,
    @get:JsonPropertyDescription("Optional artifact description")
    val description: String? = null,
)

@JsonClassDescription("Required end state for the extracted process contract")
data class ContractEndState(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable end-state id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("End-state name")
    val name: String,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this end state")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Assumption made while extracting the process contract")
data class ContractAssumption(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable assumption id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Assumption text")
    val text: String,
    @field:Valid
    @get:JsonPropertyDescription("Trace links grounding this assumption")
    val traceLinks: List<TraceLink> = emptyList(),
)

@JsonClassDescription("Trace link between generated, contract, or source elements")
data class TraceLink(
    @field:NotBlank
    @get:JsonPropertyDescription("Stable trace link id")
    val id: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Source element or evidence id")
    val sourceId: String,
    @field:NotBlank
    @get:JsonPropertyDescription("Target element id")
    val targetId: String,
    @get:JsonPropertyDescription("Alignment classification for this trace link")
    val classification: AlignmentClassification = AlignmentClassification.SUPPORTED,
    @get:JsonPropertyDescription("Evidence ids supporting this trace link")
    val evidenceIds: List<String> = emptyList(),
)

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
