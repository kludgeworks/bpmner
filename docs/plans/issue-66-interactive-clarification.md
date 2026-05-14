# Issue #66 Plan: Interactive Clarification Flow for Shell Generation

## Context

Issue #66 is part of epic #61, which moves BPMN generation from raw prose directly to a diagram into a guarded flow:

1. assess input readiness;
2. collect clarifications when the input is weak;
3. extract a source-grounded `ProcessContract`;
4. generate and validate BPMN;
5. later, run semantic alignment before writing final output.

The current `main` branch already contains the guardrail DTOs, readiness agent work, and contract extraction work
from #62, #63, and #64:

- `GenerationMode`, `BpmnGenerationStatus`, `ProcessInputAssessment`, `ClarificationQuestion`,
  `ClarificationExchange`, `BpmnGenerationContext`, and `ProcessContract` live in
  `src/main/kotlin/dev/groknull/bpmner/core/BpmnGuardrailTypes.kt`.
- `BpmnGenerationInput` has a `mode` field, defaulting to `SINGLE_SHOT`.
- `BpmnReadinessAgent.assessReadiness` can produce a `ProcessInputAssessment`.
- `BpmnContractAgent.extractProcessContract` and `BpmnContractPromptFactory.prompt` can extract and validate a
  traceable `ProcessContract`.

The current shell command still calls `BpmnGenerationUseCase.generate` without setting `mode`, so it remains
single-shot by default. `BpmnGenerationService` also discards mode and clarification history when it creates
`BpmnRequest`.

This plan assumes #65 lands first or is implemented in the same stack, because #66 needs the single-shot
readiness blocking/report behavior that returns `BpmnResult(status = NEEDS_CLARIFICATION)` without invoking
full BPMN generation.

## Target Behavior

- CLI startup through `BpmnGeneratorRunner` remains non-interactive and uses `GenerationMode.SINGLE_SHOT`.
- Spring Shell `generate` uses `GenerationMode.INTERACTIVE`.
- If readiness is `READY`, shell generation behaves like today and writes BPMN.
- If readiness is `NOT_A_PROCESS`, shell generation stops and returns the readiness report location/summary.
- If readiness is `NEEDS_CLARIFICATION`, shell displays the proposed questions, reads answers from the terminal,
  records them as `ClarificationExchange` values, re-runs readiness and contract extraction with original input plus
  answers, and continues only after the updated assessment is `READY`.
- Blank answers are ignored or treated as unanswered. If no meaningful answers are provided, or the second
  assessment is still not ready, shell returns `NEEDS_CLARIFICATION` with the updated report rather than looping
  forever.

## Design

### 1. Extend the generation input boundary

Add `clarificationHistory: List<ClarificationExchange> = emptyList()` to `BpmnGenerationInput`.

`BpmnGenerationService.generate` should preserve:

- original source text;
- style guide text;
- output path;
- mode;
- clarification history.

Because the current Embabel graph starts from `BpmnRequest`, carry `mode` and `clarificationHistory` on
`BpmnRequest` with defaults. `BpmnGenerationContext` remains a DTO for future guardrail orchestration.

### 2. Keep terminal prompting in the shell adapter

Introduce a small shell-local abstraction so tests do not need a real terminal:

```kotlin
interface BpmnShellPrompter {
    fun ask(question: ClarificationQuestion): String?
}
```

Use a Spring component implementation for production and inject the interface into `BpmnShellCommands`; tests should
provide a fake implementation.

Do not put terminal input inside `BpmnGenerationService`; it should remain usable by CLI, web, tests, and future
non-terminal adapters.

### 3. Update `BpmnShellCommands.generate`

Set the first call to:

```kotlin
BpmnGenerationInput(
    processDescription = processDescription,
    processFile = processFile,
    outputFile = output,
    styleGuide = styleGuide,
    mode = GenerationMode.INTERACTIVE,
)
```

Handle statuses explicitly:

- `GENERATED`: return `BPMN written to: ...`.
- `NOT_A_PROCESS`: return a blocked message including `reportFile`.
- `NEEDS_CLARIFICATION`: ask questions, build clarification exchanges, and call `generate` once more with the same
  input plus `clarificationHistory`.
- `ALIGNMENT_FAILED` and `VALIDATION_FAILED`: return failure text with report details where available.

For v1, use one clarification round. This satisfies the issue's "blank answers or input remains weak" acceptance
criterion and avoids an unbounded shell loop. A later issue can add configurable multi-round clarification.

### 4. Build traceable clarification evidence

For each non-blank answer, create:

- `ClarificationExchange.questionId` from the question id;
- `questionText` from the exact question shown;
- `answerText` from trimmed user input;
- `relatedMissingAreas` and `relatedDimensions` copied from the question;
- `evidence` containing a `SourceEvidence` with:
  - `sourceType = EvidenceSourceType.CLARIFICATION`;
  - `sourceRef = question.id`;
  - `text = answerText`;
  - stable id such as `clarification-${question.id}`.

This gives contract extraction enough structure to trace contract items back to clarification answers.

### 5. Re-run readiness and contract extraction with answers

The second `generate` call must pass the original input and accumulated clarification exchanges, not a concatenated
string only. Prompt factories may render clarifications into model context, but the domain object should keep them
structured.

Update readiness prompting so answered clarifications are visible to the assessor. The prompt should distinguish:

- original input;
- prior clarification questions and answers;
- style guide, if present.

`BpmnContractAgent` should pass `request.clarificationHistory` into `BpmnContractPromptFactory.prompt` so trace links
can refer to either `ORIGINAL_INPUT` or `CLARIFICATION`.

### 6. Reporting behavior

Reuse the #65 readiness report writer for both the initial and post-clarification blocked states.

Shell output should include:

- verdict;
- score;
- report file path, if written;
- a compact list of unanswered or still-missing areas;
- no "BPMN written" message unless status is `GENERATED`.

## Tests

Add or update focused unit tests first:

- `BpmnShellCommandsTest` verifies shell calls the use case with `mode = INTERACTIVE`.
- A weak first result with `NEEDS_CLARIFICATION` causes the command to ask questions and call the use case a second
  time with structured `ClarificationExchange` values.
- Blank answers do not produce clarification exchanges and the shell returns the blocked report response.
- If the second result is `GENERATED`, shell returns the success message.
- CLI runner tests keep asserting `GenerationMode.SINGLE_SHOT`.

Add service/prompt tests:

- `BpmnGenerationService` preserves mode and clarification history on `BpmnRequest`.
- Readiness prompt includes prior clarification answers.
- Contract extraction prompt or service receives clarification evidence.

Add an integration-style fake-use-case test only if the command-level tests leave a gap. Avoid real terminal I/O in
unit tests by injecting `BpmnShellPrompter`.

## Implementation Order

1. Land or merge #65 behavior into this worktree: readiness pre-check, blocked `BpmnResult`, report file writing, and
   single-shot CLI output.
2. Add `clarificationHistory` to `BpmnGenerationInput` and thread it through `BpmnRequest`.
3. Add `BpmnShellPrompter` plus a production console implementation.
4. Update `BpmnShellCommands.generate` to use `INTERACTIVE`, handle statuses, ask questions, and retry once with
   structured clarification exchanges.
5. Update readiness and contract prompts/services to render clarification history as source evidence.
6. Add tests for shell, CLI non-interactivity, readiness prompt context, and contract traceability.
7. Run the narrow shell/generation/readiness tests, then the full relevant Bazel or Maven test target.

## Risks and Open Decisions

- Spring Shell's component APIs are richer than needed here. A tiny prompt abstraction is lower risk and easier to
  test than coupling command logic to Spring Shell UI components.
- A single clarification round is intentionally conservative. If product behavior needs multiple rounds, add
  `maxClarificationRounds` to config rather than looping until ready.
- #65 is still the missing piece for true readiness blocking; this slice handles `NEEDS_CLARIFICATION` once the use
  case returns that status.
