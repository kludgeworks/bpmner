# BPMN Lint Repair Architecture

The repair subsystem turns BPMN diagrams that fail lint or schema validation into corrected diagrams without round-tripping every diagnostic through the LLM. Pkl is the single source of truth for what each lint rule is and how it can be repaired; Bazel turns that source into a JSON file the TypeScript bundle reads at runtime and into JVM classes the Kotlin app reads at startup; the Kotlin app stamps every diagnostic with its repair classification and dispatches to a local handler before falling back to the LLM.

This document describes the contract, the runtime flow, and the steps a contributor follows when adding or changing a rule.

## Ownership boundaries

| Layer | Owns |
| --- | --- |
| Pkl (`linter/pkl/`) | Static rule and repair-capability metadata. |
| `bpmnlint` (TS, via `linter/src/`) | Diagnostic discovery; local XML auto-fix handler registration. |
| TS bundle | Capability validation against handler registration; reports `getRuleCapabilities()` at startup. |
| Kotlin (`src/main/kotlin/dev/groknull/bpmner/`) | Diagnostic stamping, local-first repair orchestration, LLM fallback routing, layout, final validation. |

Rule routing is not derived from rule-name substrings or runtime heuristics; it comes from the Pkl-declared `RepairKind` on each rule.

## The repair contract

Every rule declares a `Repair`:

```pkl
class Repair {
  kind: RepairKind = "LLM_MODEL_PATCH"
  safety: RepairSafety = "LLM_ONLY"
  handler: String? = null
  replacementMap: Mapping<String, String>? = null
}
```

### `RepairKind`

| Value | Meaning |
| --- | --- |
| `LOCAL_MODEL_FIX` | A Kotlin handler edits the parsed `BpmnDefinition`. No LLM call. |
| `LOCAL_XML_FIX` | A TypeScript/JS handler edits the raw XML through the auto-fix engine. No LLM call. |
| `LLM_MODEL_PATCH` | The LLM produces a `BpmnRepairPatch` against the model; `BpmnPatchApplier` applies it. Default for any rule without an explicit `kind`. |
| `LLM_XML_REWRITE` | The LLM rewrites the BPMN end-to-end. Last-resort strategy. |
| `UNFIXABLE` | No fix is offered; the diagnostic is surfaced for human action. |

The five values are mutually exclusive and replace the older two-axis `repairRoute` × `editSurface` contract. Pkl enforces the invariant that a `LOCAL_*_FIX` kind must declare a handler and that any other kind must not.

### `RepairSafety`

Independent of `kind`. `SAFE_AUTOMATIC` means the fix can run without human review; `SAFE_MANUAL` means a human should confirm; `LLM_ONLY` is the default for LLM-routed rules.

## Pipeline at runtime

```
            ┌────────────────────────────────────────────────┐
            │ Pkl rule catalog (linter/pkl/)                 │
            │   schema/BpmnRule.pkl + rules/*.pkl            │
            └───────────────────┬────────────────────────────┘
                                │ bazel
                ┌───────────────┴───────────────┐
                ▼                               ▼
   linter-rules.json (TS)          generated JVM classes (Kotlin)
                │                               │
                ▼                               ▼
       linter-bundle.ts          PklRuleCapabilityAdapter
                │                               │
                │ getRuleCapabilities()         │ @PostConstruct
                ▼                               ▼
        validated capability map ──→ BpmnLintService.lintRuleCapabilities()
                                                │
                                                ▼
                              BpmnDiagnosticNormalizer
                              stamps each LintIssue with its `kind`
                                                │
                                                ▼
                  ┌────────────────────────────────────────────────┐
                  │ BpmnRefinementEngine repair loop               │
                  │   1. DeterministicTopologyRepairStrategy (50)  │
                  │   2. LintLocalRepairStrategy            (75)   │
                  │   3. LlmPatchRepairStrategy             (200)  │
                  │   4. FullLlmRewriteRepairStrategy       (300)  │
                  └─────────────────────┬──────────────────────────┘
                                        │ XSD-valid output
                                        ▼
                               BpmnLayoutAgent
                                        │
                                        ▼
                          Final post-layout lint
```

1. **Pkl → Bazel.** `bazel build //linter/pkl:linter_rules_json` evaluates the Pkl catalog into `linter-rules.json`. `bazel build //linter:linter_rules_json_for_ts` copies that JSON to `src/generated/linter-rules.json` for the TS bundle. Pkl-generated JVM classes are consumed via `pkl_java_library` so Kotlin reads the contract through typed classes rather than a hand-maintained DTO.
2. **TS validation.** At bundle init, `getRuleCapabilities()` walks every rule, asserts that any `LOCAL_*_FIX` kind names a registered handler, and aborts startup if not. The same capability list is exposed to Kotlin.
3. **Kotlin startup.** `BpmnLintService.@PostConstruct` calls `PklRuleCapabilityAdapter.loadCapabilities()`, builds a `Map<String, BpmnLintRuleCapability>` keyed by canonical rule id and aliases, and caches it for the lifetime of the application.
4. **Diagnostic stamping.** When the lint port returns `LintIssue`s, `BpmnDiagnosticNormalizer.normalizeLintDiagnostics()` strips the `bpmner/` or `bpmnlint-plugin-bpmner/` prefix, looks up the capability, and stamps each `BpmnDiagnostic` with `kind`, `repairSafety`, and `fixHandler`. Diagnostics from XSD or graph sources are not lint rules and carry `kind = null`.
5. **Repair loop.** `BpmnRefinementEngine` iterates strategies in `@Order` (50 → 75 → 200 → 300). The local-first invariant: `LintLocalRepairStrategy` runs *before* any LLM strategy and resolves anything with `kind == LOCAL_XML_FIX`. Fixes are applied through the TS auto-fix engine, the output is XSD-validated, and on success the diagnostic is removed from the pool. On failure, the diagnostic is annotated `[local-fix-failed: <reason>]` and forwarded to the LLM strategies as context.
6. **LLM strategies.** `LlmPatchRepairStrategy` (kind `LLM_MODEL_PATCH`) and `FullLlmRewriteRepairStrategy` (kind `LLM_XML_REWRITE`) only see diagnostics that are LLM-routed or have already failed locally. They never see resolved local diagnostics.
7. **Layout stage.** `BpmnLayoutAgent.autoFixBpmnXml` is a bounded pre-layout XML cleanup — not the main repair loop. (Issue #58 will tighten this stage to consume only `kind == LOCAL_XML_FIX` capabilities.)
8. **Final validation.** Post-layout, `BpmnLayoutAgent.validateFinalBpmnXml` runs XSD validation and a final lint pass. If any diagnostic remains, the agent fails loudly rather than re-entering the repair loop.

## Why the two artifacts (JSON for TS, JVM classes for Kotlin)

Both sides need the same metadata, but they have different runtime constraints:

- The TS bundle runs inside GraalJS in production. It reads `linter-rules.json` directly with `import catalog from "./generated/linter-rules.json"`. No JVM types are visible to it.
- The Kotlin side runs on the JVM and benefits from compile-time-checked Pkl-generated classes (`pkl_java_library`). A thin Kotlin adapter (`PklRuleCapabilityAdapter`) converts those generated classes into the internal runtime type `BpmnLintRuleCapability`. The codebase deliberately does not maintain a hand-written mirror of the JSON shape as the source contract; the only DTO is the Jackson-deserialized `RuleCatalogService.RepairMetadata`, used to read the same JSON file from the classpath at startup and validated against the same Pkl-emitted shape.

If the Pkl schema changes, regenerating both targets (`//linter/pkl:linter_rules_json` and the JVM library) is mandatory; the build will fail loudly if either side falls out of step.

## Contributor checklist

### Adding a new rule

1. Create `linter/pkl/rules/<Name>.pkl` amending `BpmnRule.pkl`. Set `kind`, `safety`, and `handler` (if local) explicitly. Defaults are `LLM_MODEL_PATCH` / `LLM_ONLY` / `null`.
2. Add the rule to `Catalog.pkl` so it ships in the generated artifacts.
3. If `kind` is `LOCAL_XML_FIX`, register a handler in `linter/src/auto-fix/registry.ts`. TS startup will refuse to boot if the handler is missing.
4. If `kind` is `LOCAL_MODEL_FIX`, register a Kotlin handler under `repair/internal/` and wire it into the repair strategy that owns model-level local fixes. (Currently parked behind #30.)
5. Add fixtures and tests under `linter/test/`.
6. Regenerate Bazel artifacts: `bazel build //linter/pkl:linter_rules_json //linter:linter_rules_json_for_ts`.
7. Run `bazel test //src/test:all //linter:all //linter/pkl:all --test_tag_filters=-integration`.

### Changing a rule's `RepairKind`

1. Edit the rule's `.pkl` file. If the change moves the rule into `LOCAL_*_FIX`, also add the matching handler; if it moves the rule out, delete the now-orphaned handler.
2. Update or add tests that assert the new behaviour (capability test in `linter/test/capabilities.test.ts` and any strategy test that stamps a diagnostic with the old kind).
3. Regenerate artifacts and run the test sweep as above.

### Adding a new local XML handler

1. Implement the handler in `linter/src/auto-fix/handlers/`.
2. Register it in `linter/src/auto-fix/registry.ts` under the handler name declared in the Pkl `handler` field.
3. The TS bundle's `getRuleCapabilities()` will fail at startup if a `LOCAL_XML_FIX` rule references an unregistered handler.

### Adding a new local model handler

1. Implement the handler in `src/main/kotlin/dev/groknull/bpmner/repair/internal/`.
2. Wire it into the Kotlin local-repair strategy that handles `kind == LOCAL_MODEL_FIX` (currently #30).
3. Capability stamping requires no change — `BpmnDiagnosticNormalizer` picks up the new handler name from the Pkl-declared metadata.

### Validating handler availability

Both sides validate at startup. There is no separate dev-time check to run. If a `LOCAL_*_FIX` rule references a handler that does not exist, the TS bundle throws during `getRuleCapabilities()` and the JVM app refuses to start with a clear error message.

### Updating tests after a contract change

- Kotlin test fixtures that build `BpmnLintRuleCapability` or `BpmnDiagnostic` literals must use the current `kind` enum values.
- TS capability tests assert against `cap.kind`; pre-existing tests against `cap.route` / `cap.editSurface` are obsolete and should be deleted in the same change that introduces them.
- Pkl `CatalogTest.pkl` and `SchemaTest.pkl` assert against `rule.repair.kind` directly.
