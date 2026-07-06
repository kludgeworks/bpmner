# ADR-005: Prompt contribution lives in the `bpmn` kernel

**Decision 1:** `BpmnRequest.styleGuideContribution(): String` lives as a top-level extension
in the `bpmn` kernel (`bpmn/BpmnRequestContribution.kt`). It depends only on a `bpmn` type
and the Kotlin stdlib — no Embabel or framework imports cross the kernel boundary. Each call
site wraps it locally with `PromptContributor.fixed(request.styleGuideContribution())`.

**Track A:** The former `BpmnRequestPromptContributor` cross-tier interface (which lived in
the dissolved `config/` module and was implemented in `generation/`) is deleted. Module tests
for `readiness`, `contract`, and `alignment` require no stub for it.

Origin: epic #424 S7, Decision 1 current on `main`. See `../architecture.md` §3.
