# How `bpmner` generates a BPMN diagram

`bpmner` turns a plain-language description into a BPMN 2.0 diagram via a chain
of `@Agent`s orchestrated by the Embabel framework's GOAP planner. Each
`@Action` declares its input types (preconditions) and output type (effect),
and Embabel chains them automatically by matching types on the blackboard.

## High-level flow

`BpmnRequest` → **readiness** → **contract** → **generation** → **repair** →
**layout** → **alignment** → **finalize** → `BpmnResult`

## Stage-by-stage

### 1. Readiness assessment
`BpmnReadinessAgent.assessReadiness`
(`readiness/internal/adapter/inbound/BpmnReadinessAgent.kt:46`)

LLM call that decides whether the raw input has enough process detail to
proceed, producing a `ProcessInputAssessment`. A
`BpmnReadinessAssessedEvent` is published.

### 2. Contract extraction
`BpmnContractAgent.extractProcessContract`
(`contract/internal/adapter/inbound/BpmnContractAgent.kt:53`)

LLM call that extracts a source-grounded `ProcessContract` (participants,
triggers, steps, branches, end conditions). It is validated into a
`ValidatedProcessContract`; an invalid contract aborts generation with the
formatted issues.

### 3. Generation
`BpmnGeneratorAgent` (`generation/internal/adapter/inbound/BpmnGeneratorAgent.kt`)

A multi-step graph build:

- `createProcessOutline` — LLM call producing a typed `BpmnDefinition`
  (the outline).
- `validateOutline` — local checks (e.g., non-blank `processId`/`processName`).
- `generatePhasePlans` / `validatePhasePlans` — currently a single `phase:main`
  plan, structured for future multi-phase work.
- `composeProcessGraph` — assembles a `ComposedProcessGraph` with per-element
  ownership refs.
- `assignOwnership` — stable ownership metadata for every node, sequence, and
  its `*_di` shape.
- `assignLayout` — produces a `LaidOutProcessGraph`.
- `renderBpmnXml` — calls `BpmnRenderer` to emit BPMN 2.0 XML and publishes
  `BpmnGeneratedEvent`.

### 4. Repair loop
`BpmnRepairAgent.repair` → `BpmnRefinementEngine`
(`repair/internal/domain/BpmnRefinementEngine.kt`)

Iteratively validates and repairs the rendered XML until it passes XSD +
bpmn-lint, or attempts are exhausted. Three repair strategies run in order
per attempt:

- `LintLocalRepairStrategy` — deterministic handlers (e.g., split/join fork
  gateway, bypass gateway, inserting converging gateways).
- `LlmPatchRepairStrategy` — LLM produces a structured patch applied by
  `BpmnPatchApplier`.
- `FullLlmRewriteRepairStrategy` — last-resort full rewrite.

The engine detects no-progress (unchanged diagnostics/patches) and bails out.
Publishes `BpmnValidationPassedEvent` on success. Output: `ValidatedBpmnXml`.

### 5. Auto-layout
`BpmnLayoutAgent` (`layout/internal/adapter/inbound/BpmnLayoutAgent.kt`)

- `autoFixBpmnXml` — bounded, XML-local cleanup limited to lint issues whose
  capability `kind == LOCAL_XML_FIX`. Output rejected if it becomes XSD-invalid;
  falls back to the input XML on any failure. **Not** a semantic repair stage.
- `layoutBpmnXml` — calls `BpmnLayoutPort` to compute coordinates.
- `validateFinalBpmnXml` — final XSD + post-layout lint. Throws
  `BpmnFinalValidationException` if anything remains.

### 6. Alignment check
`BpmnAlignmentAgent.checkAlignment`
(`alignment/internal/adapter/inbound/BpmnAlignmentAgent.kt:52`)

LLM call comparing a summary of the BPMN against the original
`ProcessContract`. Produces an `AlignmentVerdict`; a `FAILED` verdict throws
`BpmnAlignmentException`. Publishes `BpmnAlignmentCheckedEvent`.
Output: `AlignedBpmnXml` (XML + alignment report).

### 7. Finalize
`BpmnGeneratorAgent.finalizeBpmn`
(`generation/internal/adapter/inbound/BpmnGeneratorAgent.kt:231`)

The terminal `@AchievesGoal` action (exported remotely as `generateBpmn`).
Writes XML to `request.outputFile` if provided and returns a `BpmnResult`
containing the XML, the alignment report, and a `GENERATED` status.

## Key design notes

- Three concerns are intentionally separated: **semantic repair** (repair
  agent, full strategy stack), **local XML cleanup** (layout agent, only
  `LOCAL_XML_FIX` rules), and **layout** (deterministic).
- LLM endpoints are configured per stage via `BpmnConfig`
  (`readinessAssessor`, `contractExtractor`, `generator`, `alignmentValidator`,
  plus repair runners), so each step can use a different model.
- Events (`BpmnReadinessAssessedEvent`, `BpmnGeneratedEvent`,
  `BpmnValidationPassedEvent`, `BpmnValidationFailedEvent`,
  `BpmnAlignmentCheckedEvent`) feed the `observability` module for logging
  and metrics without coupling stages.
