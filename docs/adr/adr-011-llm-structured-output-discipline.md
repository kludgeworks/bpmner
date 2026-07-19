# ADR-011: LLM structured-output discipline

**Schema design.** Minimal, violations-only schemas where the role supports it (`BpmnAlignmentTypes.kt`'s
`AlignmentFindings` playbook, #171). Flat enums over discriminated unions or parallel-enum pairs —
`ReadinessDimension` unifies what used to be `ReadinessDimension` + `MissingProcessArea`; the
dimension-vs-gap distinction is carried by which field holds the value, not by a second type (#611).
Worked `@JsonClassDescription`/`@JsonPropertyDescription` instructions over prose-only guidance — the
schema is what the model actually reads. Bias fields no downstream consumer requires non-null toward a
safe default rather than a mandatory field with no fallback (`SourceEvidence.sourceType`, #611): a value
nothing reads doesn't need to gate the LLM retry loop.

**Failure handling.** Catch only the stable `com.embabel.agent.core.support.InvalidLlmReturn*` pair via
`llm/StructuredOutputReliability.kt`'s `publishOnInvalidLlmReturn` seam. That catch **is** the
transient-vs-deterministic classification: these two types are always deterministic (malformed/invalid
model output), so anything else — network, rate limit — is left to the framework's default retry. Pair
the seam with `ActionRetryPolicy.FIRE_ONCE` at whichever `@Action` actually owns the LLM call's retry
boundary — traced per-role, not assumed; the boundary is sometimes the pipeline action, sometimes an
isolated sub-agent-process's own action (readiness, #611).

**Explicit #543 callback:** nothing in this discipline is a hand-authored keyword/verb list judging
prose. The readiness enum fix is a schema/vocabulary change (fewer enum names to confuse), not new
prose-content determinism.

Applied so far: readiness, alignment (#611, epic #592 Stage 1). Stage 2 (#592) applies it to contract,
repair, generation.

Origin: epic #592, issue #611.
