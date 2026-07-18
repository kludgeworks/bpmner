<!-- markdownlint-disable MD013 -->

# ADR-011: JVM-native ELK BPMN layout replaces GraalJS/`yet-another-bpmn-auto-layout`

**Decision:** BPMN layout runs entirely on Eclipse ELK Layered
(`org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0`), invoked in-process and
synchronously through a skeleton-then-refine two-phase pipeline: ELK lays out the flow
skeleton, then a deterministic BPMN placement pass owns boundary-event, label, and
artifact placement conventions ELK does not model. `ElkBpmnLayouter` is the sole
`BpmnLayoutPort` adapter. The retained BPMN profile is deliberately contracted (see
[`../bpmn-profile.md`](../bpmn-profile.md)). Full design rationale, prior-art analysis,
and the amendment history live in `plans/557/ARCHITECTURE.md`.

**Consequences:**

- GraalJS, GraalVM Polyglot, and the `yet-another-bpmn-auto-layout` npm bundle
  (packaging, classpath resources, Promise-bridging) are removed entirely.
- The supported BPMN vocabulary is contracted: event subprocesses, compensation,
  escalation, signal events, data objects/stores, and non-interrupting boundary
  variants are removed pending re-entry after layout support exists for their geometry
  (issues [#558](https://github.com/kludgeworks/bpmner/issues/558),
  [#559](https://github.com/kludgeworks/bpmner/issues/559),
  [#560](https://github.com/kludgeworks/bpmner/issues/560)).
- Existing BPMN-DI geometry is discarded and regenerated on every layout call;
  non-geometry DI (colours, `bioc:`, extensions) is merged forward, not wiped.
- Module confinement for ELK is enforced by `BpmnerModulithTest.kt`'s `modules.verify()`
  (Spring Modulith cross-module boundary check) rather than a dedicated ELK/GraalJS
  ArchUnit rule — that substitution is epic-owner-directed and permanent; no future
  stage reintroduces the dedicated rule.

Origin: epic [#557](https://github.com/kludgeworks/bpmner/issues/557). See
`plans/557/ARCHITECTURE.md` §§ AD-557-02 through AD-557-18 for the full design history.

## Acceptance traceability

Every [#557](https://github.com/kludgeworks/bpmner/issues/557) acceptance checkbox,
mapped to its verifying test target, repository search, documented rejection, or
explicit non-goal:

| # | #557 checkbox (abbreviated) | Trace |
| --- | --- | --- |
| 1 | JVM-native ELK sole producer of geometry | `ElkBpmnLayouter` (`@InfrastructureRing @Service`) is the sole `BpmnLayoutPort` bean; `BpmnLayoutPortCorpusIntegrationTest` exercises it end to end |
| 2 | Final + live snapshots use JVM geometry | `web/src/snapshot-import.ts` has no `layoutProcess`/client-layout call; DI-less intermediate snapshots resolve `pending` (AD-557-06) |
| 3 | `yet-another-bpmn-auto-layout` and its integrations absent | Repo-wide search: zero hits outside `plans/`/git history (see [sweep](#full-repo-sweep) below) |
| 4 | GraalJS/Polyglot absent | `MODULE.bazel`: zero `org.graalvm.polyglot:*` |
| 5 | JS bundle/packaging/classpath/Promise-bridge absent | No `layout` JS directory, no `layout_bundle_resources` target, no `bpmn-layout-bundle` reference anywhere in the tree |
| 6 | Existing BPMN-DI discarded and regenerated | **Amended, documented as such:** geometry (bounds/waypoints/labels) is discarded and regenerated; non-geometry DI (colours, `bioc:`, extensions) is merged forward — `ElkToBpmnDiWriter.captureExistingShapes`/`captureExistingEdges`, per AD-557-02's DI-merge-not-wipe note (`plans/557/ARCHITECTURE.md:84`) |
| 7 | Every accepted shape/connection has DI | `BpmnLayoutAgent.validateFinalBpmnXml`'s node/edge-DI-coverage checks; `ElkGoldenLayoutTest` (22-fixture corpus) |
| 8 | Passes XSD + semantic validation | `validateFinalBpmnXml`'s XSD check; `BpmnDefinitionValidator` |
| 9 | Imports into bpmn-js without error | **Superseded, documented as such:** retired as a JS-throw assertion — `bpmn-js`'s importer demotes almost every referential-integrity failure to a non-throwing warning (verified directly in `BpmnTreeWalker.visitIfDi` source), so the check was never rigorous. Replaced permanently by `BpmnLayoutAgent.validateFinalBpmnXml`'s JVM-native structural check, run on every production layout call (AD-557-17) |
| 10 | Correct containment/attachment | `CollaborationShapePlacement`, `BoundaryShapePlacement` processors; corpus fixtures including `collab-subprocess`, `boundary-*` |
| 11 | Flows connect correct endpoints | AD-557-17 edge-endpoint-resolution check in `validateFinalBpmnXml` |
| 12 | No material overlap, legible labels, sane routing | `ElkGoldenLayoutTest` (22 fixtures); `LabelMetricsTest`, `LabelWrapTest` |
| 13 | Deterministic output | `ElkBpmnLayouterTest`, `` `repeated layout of same input produces stable DI geometry` `` — asserts byte-identical DI across two layout calls on the same input, per fixture |
| 14 | Cyclic flows render validly | `collab-lanes-loopback` fixture; `LoopBackEdgeArcsTest` |
| 15 | Topology-policy violations fail with diagnostics | Pkl rules "No Implicit Split" (`TopologyCheck.NO_IMPLICIT_SPLIT`), "Fake Join" (`TopologyCheck.NO_FAKE_JOIN`), "No Gateway Join Fork" (`TopologyCheck.NO_JOIN_FORK`); `BpmnDefinitionValidator.kt:145-148` (disconnected flow nodes) — all four are dedicated, live rules, not a generation-time-only contract shape; see [`../bpmn-profile.md`](../bpmn-profile.md#topology-policy) |
| 16 | Removed constructs absent end-to-end | `BpmnNode`/`BpmnEventDefinition` sealed interfaces carry no Signal/Escalation/Compensation/EventSubprocess/DataObject/DataStore subtype; repo-wide vocabulary search returns zero hits outside `plans/` |
| 17 | Removed-construct inputs fail explicitly | `BpmnUnrecognizedNode`/`BpmnUnrecognizedEventDefinition` parser fallbacks; `BpmnRepairAdvancer.kt:51-58` pre-flight `UNFIXABLE` short-circuit via `BpmnUnrecognizedElementScanner.scan`; `BpmnSubset` rule (`BpmnDefinitionValidator.kt:393-394`); `BpmnXmlToDefinitionConverterTest.kt`, `` `parse surfaces removed signal event definitions as unsupported` `` |
| 18 | Deferred capabilities documented as future issues | [#558](https://github.com/kludgeworks/bpmner/issues/558), [#559](https://github.com/kludgeworks/bpmner/issues/559), [#560](https://github.com/kludgeworks/bpmner/issues/560) — filed, linked from [`../bpmn-profile.md`](../bpmn-profile.md#deferred-capabilities) |
| 19 | XML-projection workaround no longer required | `BpmnLayoutXmlProjector.kt` deleted in 557-5 — confirmed absent from `main` |
| 20 | Repo documents retained profile + topology | [`../bpmn-profile.md`](../bpmn-profile.md) — this stage's primary deliverable |

### Full-repo sweep

Repository-wide search for `yet-another-bpmn-auto-layout`, `graalvm.polyglot`,
`bpmn-layout-bundle`, `BpmnLayoutService`, `BpmnLayoutXmlProjector` outside `plans/` and
git history: zero hits. `GraalJS` (the term) remains only in Vale's spell-check
vocabulary (`styles/config/vocabularies/bpmner/accept.txt`,
`styles/BPMN/Terms.yml`) so this document and `bpmn-profile.md` can name the removed
dependency in prose without tripping the linter — it is not a usage reference.
`tools/js/polyfills.ts`, an unused GraalJS-interop shim left over from the removed
layout bundle (zero callers, confirmed by repo-wide search), was deleted as part of this
stage's sweep.
</content>
