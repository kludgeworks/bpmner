# Issue 63 Plan: BPMN Input Readiness Assessment

## Goal

Implement issue #63 as the first consumer of the guardrail DTOs from issue #62.
This slice should add an Embabel readiness assessment action that returns a
`ProcessInputAssessment` for raw BPMN generation input, with deterministic
post-checks and focused tests.

This issue must not block generation, write readiness reports, collect shell
clarifications, extract `ProcessContract`, generate from contracts, summarize
`BpmnDefinition`, or run semantic alignment. Those belong to issues #64-#70.

## Context

Epic #61 defines the guarded generation pipeline:

1. Assess process readiness.
2. Clarify or block weak inputs later in the pipeline.
3. Extract a source-grounded `ProcessContract`.
4. Generate `BpmnDefinition` from that contract.
5. Run existing technical validation, repair, and layout.
6. Summarize generated BPMN and run semantic alignment.

Issue #63 owns only step 1. It should make the assessment capability available
to the agent graph while preserving current generation behavior.

The existing issue #62 work already added:

- `ProcessInputAssessment`
- `ReadinessVerdict`
- `ReadinessDimension`
- `MissingProcessArea`
- `ClarificationQuestion`
- `SourceEvidence`
- `BpmnGenerationContext`
- `BpmnResult.readinessReport`

## Scope Boundaries

In scope:

- Add a readiness agent/action that takes `BpmnRequest` and returns
  `ProcessInputAssessment`.
- Prompt the model for structured assessment output only.
- Require missing/unsupported facts to remain missing, not invented.
- Add deterministic post-checks over the original source text and the model's
  assessment.
- Add configurable scoring thresholds with defaults:
  - `READY`: `>= 75`
  - `NEEDS_CLARIFICATION`: `40..74`
  - `NOT_A_PROCESS`: `< 40` or no repeatable workflow detected
- Add tests for strong, weak, and non-process input using fakes and/or direct
  deterministic post-checks.

Out of scope:

- No single-shot blocking or readiness report files. That is issue #65.
- No interactive shell prompting or clarification answer collection. That is
  issue #66.
- No process contract extraction or validation. That is issue #64.
- No prompt change that makes BPMN generation consume contracts. That is issue
  #67.
- No semantic alignment or alignment blocking. That is issue #69.
- No end-to-end guardrail observability suite. That is issue #70.

## Model Gaps To Fix First

Issue #63 names readiness dimensions that are more complete than the current
issue #62 enum. Extend `ReadinessDimension` before implementing the action:

- `PROCESS_BOUNDARY`
- `START_TRIGGER`
- `END_STATES`
- `ACTIVITIES`
- `SEQUENCE_ORDER`
- `ACTORS_ROLES`
- `DECISIONS_BRANCHES`
- `EXCEPTIONS_REWORK`
- `INPUTS_OUTPUTS_ARTIFACTS`
- `SCOPE_CLARITY`
- `BPMN_SUITABILITY`
- `TRACEABILITY_TO_SOURCE`

Keep old enum values only if needed for compatibility with existing tests, but
prefer updating tests to the issue #63 vocabulary because backwards
compatibility is not required in this epic slice.

Also extend `MissingProcessArea` enough to support deterministic checks and
targeted questions:

- `PROCESS_BOUNDARY`
- `START_TRIGGER`
- `END_STATE`
- `ACTIVITY_SEQUENCE`
- `ACTOR_RESPONSIBILITY`
- `DECISION_CRITERIA`
- `EXCEPTION_HANDLING`
- `INPUT_ARTIFACT`
- `OUTPUT_ARTIFACT`
- `BPMN_PROCESS_SUITABILITY`
- `SOURCE_TRACE`

## Proposed Production Changes

### 1. Configuration

Extend `BpmnConfig` in `src/main/kotlin/dev/groknull/bpmner/core/BpmnConfig.kt`
with a readiness section:

```kotlin
val readiness: BpmnReadinessConfig = BpmnReadinessConfig()
```

Add:

```kotlin
data class BpmnReadinessConfig(
    val readyThreshold: Int = 75,
    val clarificationThreshold: Int = 40,
    val minimumActivityCount: Int = 2,
    val maxClarificationQuestions: Int = 5,
)
```

Add a default `Actor<Persona>` for readiness assessment, either as
`BpmnConfig.readinessAssessor` or nested under the readiness config. Use a
persona that is deliberately conservative about missing facts.

### 2. Readiness Agent

Add a new primary adapter:

`src/main/kotlin/dev/groknull/bpmner/readiness/internal/adapter/inbound/BpmnReadinessAgent.kt`

Shape:

```kotlin
@PrimaryAdapter
@Agent(description = "Assess whether source text is ready for BPMN generation")
internal class BpmnReadinessAgent(
    private val config: BpmnConfig,
    private val postChecker: BpmnReadinessPostChecker,
) {
    @Action(description = "Assess raw BPMN generation input for process readiness")
    fun assessReadiness(
        request: BpmnRequest,
        context: OperationContext,
    ): ProcessInputAssessment {
        val modelAssessment = config.readinessAssessor
            .promptRunner(context)
            .withPromptContributor(request)
            .createObject(readinessPrompt(request), ProcessInputAssessment::class.java)

        return postChecker.apply(request, modelAssessment)
    }
}
```

Do not add this action as a prerequisite to the existing `generateBpmn` goal in
this issue. It should be callable by the agent graph as a capability and covered
by unit tests, but issue #65 will wire it into `BpmnGenerationService`.

### 3. Prompt Factory

Add a small prompt factory, either private to the agent or in:

`src/main/kotlin/dev/groknull/bpmner/readiness/internal/adapter/inbound/BpmnReadinessPromptFactory.kt`

Prompt requirements:

- Return only `ProcessInputAssessment`.
- Score every configured `ReadinessDimension`.
- Use the issue #63 verdict rules.
- Treat unsupported facts as missing.
- Tie every clarification question to `relatedDimensions` and
  `relatedMissingAreas`.
- Prefer specific, answerable questions over broad prompts.
- Include source evidence only from the original input text.
- Do not invent actors, triggers, end states, exceptions, or artifacts.

### 4. Deterministic Post-Checks

Add a domain service:

`src/main/kotlin/dev/groknull/bpmner/readiness/internal/domain/BpmnReadinessPostChecker.kt`

Responsibilities:

- Clamp scores to `0..100`.
- Recompute the final verdict from thresholds after adjustments.
- Add or lower dimension scores for deterministic failures:
  - no start trigger lowers readiness and adds `START_TRIGGER`
  - no end state lowers readiness and adds `END_STATE`
  - fewer than two process-like activities lowers readiness and adds
    `ACTIVITY_SEQUENCE`
  - no sequence indicators lowers readiness and adds `ACTIVITY_SEQUENCE`
  - no process-like verbs can force `NOT_A_PROCESS`
- Ensure the final assessment has all required dimensions.
- Ensure `NEEDS_CLARIFICATION` assessments have at least one clarification
  question for missing core areas.
- Trim clarification questions to `maxClarificationQuestions`.

Keep the heuristics deliberately simple and local:

- process-like verbs: approve, review, submit, receive, validate, create,
  update, notify, ship, invoice, pay, reject, escalate, assign, close
- start trigger markers: when, after, once, starts, begins, receives, submitted,
  requested
- end-state markers: ends, complete, completed, closed, shipped, paid, rejected,
  approved, archived
- sequence markers: then, next, after, before, if, otherwise, finally, once

The deterministic checker should not try to extract a full process contract.
That is issue #64.

### 5. Optional Port For Future Wiring

If useful for issue #65, introduce a small port without using it yet:

```kotlin
internal interface BpmnReadinessAssessmentPort {
    fun assess(request: BpmnRequest): ProcessInputAssessment
}
```

This is optional. The cleaner issue #63 cut is to let the Embabel action be the
public agent capability and postpone service-level invocation until issue #65.

## Test Plan

Add focused tests:

- `BpmnReadinessPostCheckerTest`
  - strong process text remains `READY`
  - weak process text becomes `NEEDS_CLARIFICATION`
  - non-process text becomes `NOT_A_PROCESS`
  - missing start trigger lowers the relevant dimension
  - missing end state lowers the relevant dimension
  - fewer than two activities lowers readiness
  - no sequence indicators lowers readiness
  - clarification questions are capped and tied to missing dimensions

- `BpmnReadinessAgentTest`
  - fake prompt runner/model returns a structured assessment and the agent
    returns the post-checked result
  - prompt text contains "do not invent" and structured-output constraints

- Update `BpmnGuardrailTypesTest` if enum names change.

Run:

```text
bazel test //src/test:BpmnReadinessPostCheckerTest
bazel test //src/test:BpmnReadinessAgentTest
bazel test //src/test:BpmnGuardrailTypesTest
bazel test //src/test:ktlint_check
```

Then run either:

```text
bazel test //src/test:...
```

or the narrower generation/readiness suite if full tests are too slow.

## Implementation Order

1. Extend readiness enums and update existing guardrail DTO tests.
2. Add readiness config defaults and persona.
3. Add `BpmnReadinessPromptFactory`.
4. Add `BpmnReadinessPostChecker` with deterministic heuristics.
5. Add `BpmnReadinessAgent` as the Embabel `@Action`.
6. Add post-checker tests.
7. Add agent/prompt tests using a fake model path consistent with existing
   Embabel tests in the repo.
8. Run ktlint and targeted Bazel tests.

## Acceptance Mapping

- Strong process text returns `READY`: covered by post-checker/agent tests.
- Weak process text returns `NEEDS_CLARIFICATION`: covered by missing trigger,
  end state, sequence, or actor tests.
- Non-process text returns `NOT_A_PROCESS`: covered by no process-like verbs
  and no repeatable workflow tests.
- Assessment includes missing areas and clarification questions: covered by
  weak input tests.
- Unsupported details are not represented as facts: enforced through prompt
  instructions in issue #63; deeper source-grounding and trace validation stays
  with issue #64.

## Follow-Up Hand-Off

Issue #65 should call the readiness capability before generation and map
`NEEDS_CLARIFICATION` or `NOT_A_PROCESS` to blocked `BpmnResult` values.

Issue #66 should turn `ClarificationQuestion` into shell prompts and store
answers as `ClarificationExchange`.

Issue #64 should consume the assessment when extracting `ProcessContract` and
require every contract item to carry trace links.
