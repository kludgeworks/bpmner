<!-- markdownlint-disable MD013 -->

# BPMN Profile

`bpmner` aims to support as much of the BPMN 2.0 spec as possible.

## Vocabulary

| Category | Construct | Status |
| --- | --- | --- |
| Events | Start, end, intermediate (catch/throw), boundary | 🟢 |
| Events | Event subprocess | 🔴 |
| Event definitions | None, timer, message, error, terminate | 🟢 |
| Event definitions | Signal, escalation, compensation | 🔴 |
| Boundary events | Interrupting timer, interrupting error | 🟢 |
| Boundary events | Non-interrupting variants | 🔴 |
| Tasks | Task, user, service, script, business-rule, send, receive | 🟢 |
| Activities | Call activity, embedded subprocess | 🟢 |
| Activity characteristics | Standard-loop, multi-instance | 🟢 |
| Gateways | Exclusive, parallel, inclusive, event-based | 🟢 |
| Collaboration | Participants (pools), black-box participants, cross-pool message flows | 🟢 |
| Collaboration | Lanes | 🟢 |
| Artifacts | Text annotations, ordinary associations | 🟢 |
| Data | Data objects/stores/references, data input/output associations | 🔴 |

> [!NOTE]
> Removed constructs are not silently dropped: generation fails with a typed
> `UNFIXABLE` diagnostic rather than partially rendering.

## Placement exception ledger

The ELK layout engine owns node/edge geometry, hierarchy-aware layering, crossing
minimisation, and orthogonal routing (epic [#591](https://github.com/kludgeworks/bpmner/issues/591)).
A small, named set of post-ELK processors retains ownership of BPMN semantics ELK
cannot represent. Every processor below has a semantic rationale, an owning test, and
(where it relocates an already-ELK-placed node) a `PlacementGuardTest` ledger entry —
`PlacementGuardTest` fails if any new post-ELK node move is undeclared.

Collaboration-presentation owners (`CollaborationShapePlacement`, `WhiteBoxPoolBandPlacement`,
`ExternalBlackBoxBandPlacement`) are documented in `plans/591/ARCHITECTURE.md`'s ADRs rather
than duplicated here; all three are declared `PlacementGuardTest` owners.

| Owner | BPMN semantic reason | Owning test | `PlacementGuardTest` status |
| --- | --- | --- | --- |
| `HandlerComponentAlignment` | Boundary-event handler continuations are a BPMN structure ELK does not place; `.Move` shifts each handler component clear of its host, `.Repair` re-anchors the sequence flows it moves. | `HandlerComponentAlignmentTest.kt` | declared owner |
| `SubprocessEndStraddle` | BPMN convention requires a terminating end event to straddle its expanded subprocess's boundary; ELK does not enforce this. | `SubprocessEndStraddleTest.kt` | declared owner |
| `SubprocessSpineCentring` | BPMN convention aligns a subprocess's happy-path spine on its vertical centre; ELK's orthogonal layout does not guarantee it. | `SubprocessSpineCentringTest.kt` | declared owner |
| `ExceptionEdgeRoutes` | Boundary-event exception routes are deterministic from placed boundary/handler bounds, with no ELK-modelled obstacle detection (`ExceptionEdgeRoutes.kt:21-22`). | `ExceptionEdgeRoutesTest.kt` | not ledgered — routes an edge, not a node move |
| `LoopBackEdgeArcs` | Loop-back flows are excluded from the ELK graph entirely (ELK's layered algorithm is acyclic), so this is the sole owner of both their arc route and their label (`LoopBackEdgeArcs.kt:43-45`). | `LoopBackEdgeArcsTest.kt` | not ledgered — routes an edge, not a node move |
| `BoundaryShapePlacement` | Places boundary-event shapes on their host's bottom edge and re-sorts handler y-order to match boundary x-order, eliminating crossings ELK's declaration-order sequencing would otherwise introduce (`BoundaryShapePlacement.kt:22-26`). | `BpmnPlacementPassTest.kt` + golden corpus | structurally exempt (boundary shapes outside lane bands) |
| `BoundaryLabelPlacement` | Applies the retained BPMN-specific label rule for boundary events and their hosts (`BoundaryLabelPlacement.kt:15`). | `BpmnPlacementPassTest.kt` + golden corpus | not ledgered — labels only |
| `ArtifactPlacement` | Text annotations and groups are never mapped into the ELK graph, so they cannot distort primary control-flow layout; this processor derives their position from the already-final flow-node shapes. | `BpmnPlacementPassTest.kt` + golden corpus | structurally exempt (artifacts) |
| `AssociationEdges` | Associations are never mapped into the ELK graph for the same reason; routed only after every shape has its final position. | `BpmnPlacementPassTest.kt` + golden corpus | not ledgered — routes an edge, not a node move |

Every owner's fixture coverage lives under `src/test/resources/layout-fixtures/`; the
deterministic golden corpus and `PlacementGuardTest`'s fixture list are the executable
proof that no undeclared post-ELK relocation exists.

## Known limitations and recommended follow-up

Two items were investigated during epic #591's closing stage
([#608](https://github.com/kludgeworks/bpmner/issues/608)) and are recorded here rather
than silently carried forward.

### `representative-process`'s `Flow_default` bend count is accepted, not fixed

`representative-process.expected.bpmn`'s unnamed `Flow_default`
(`Gw_split` → `Task_handle_fail`) has 6 waypoints versus 4 before #607, an indirect
side effect of ELK's crossing-minimisation reacting to a sibling edge's label dummy
node. A full sweep of every named-candidate ELK option
(`CONSIDER_MODEL_ORDER_STRATEGY = PREFER_EDGES`, `SEPARATE_CONNECTED_COMPONENTS = false`,
`NODE_PLACEMENT_STRATEGY = LINEAR_SEGMENTS`) found: the first two either had no effect or
didn't touch this edge; the third fixed it but regressed 7 of the corpus's other 20
fixtures (breaking the primary-flow straight-baseline guarantee `NETWORK_SIMPLEX` exists
for — a higher-priority readability property than this edge's bend count per AD-591-05).
No safe fix exists without a disproportionate, HITL-reviewed reflow of the deterministic
corpus for one unnamed single-hop edge. The current output remains the accepted,
deterministic golden; this is not a correctness or DI-validity defect.

### White-box pool-band stacking is untested and defective beyond two participants

While building a BPMN MIWG interchange fixture from
[reference test case C.2.0](https://github.com/bpmn-miwg/bpmn-miwg-test-suite/blob/master/Reference/C.2.0.bpmn)
("Buying at Amazon Collaboration", 4 participants, 5 message flows, an embedded
subprocess, and a boundary error event), `WhiteBoxPoolBandPlacement`/
`CollaborationShapePlacement` produced multiple confirmed defects: the first-declared
participant's own content (start event, tasks, and a large embedded subprocess) was
translated to entirely different coordinates than its own projected pool band, leaving
it rendered outside/above its own pool; a descendant end event bled into a neighbouring
pool's lane; and several same-participant sequence-flow routes around the
gateway/subprocess network rendered with duplicate-space kinks. Every existing
collaboration fixture in this corpus has exactly two participants and no embedded
subprocess inside the first-declared one, so this scaling case was never exercised.
Fixing it requires changes to `#607`-owned collaboration-presentation code, which is out
of `#608`'s declared scope (`#608` does not reassess collaboration presentation). The
draft fixture was discarded rather than committed with known-broken output; see the
epic's closing PR discussion for the full investigation record.

## Epic #591 acceptance-criteria traceability

Every checkbox in epic [#591](https://github.com/kludgeworks/bpmner/issues/591)'s acceptance
criteria, mapped to the test/fixture/ADR that satisfies it. No criterion is unresolved.

| # | Acceptance criterion (abridged) | Satisfying evidence |
| --- | --- | --- |
| 1 | Explicit, tested ELK layout profile grouped by topology/geometry/labels/connections/routing/ordering/artifacts/validation | `ElkOptionBehaviourTest.kt`, `BpmnToElkMapper.applyRootLayoutOptions` |
| 2 | Pools/lanes/expanded subprocesses as ELK compound nodes with hierarchy-aware layout | `BpmnToElkMapperTest.kt`, `CollaborationShapePlacementTest.kt` (#606) |
| 3 | Flow elements/sequence flows use ELK nodes/ports/edges; ELK edge sections are the DI waypoint source | `ElkToBpmnDiWriterTest.kt`, `ElkLayoutResultCopy` (#607) |
| 4 | Boundary-event attachment via ELK port geometry; shapes projected from that geometry | `HandlerComponentAlignmentTest.kt`, placement exception ledger above |
| 5 | Node/edge labels sized to the bpmn-js rendering contract | `LabelMetricsTest.kt`, `LabelWrapTest.kt` |
| 6 | ELK-native routing/crossing-minimisation/ordering; tighter constraints justified by fixture+HITL evidence | "`representative-process`'s `Flow_default` bend count is accepted, not fixed" above; `plans/591/PLAN-608.md` gap-3 addendum |
| 7 | Model order used only as a tie-breaker unless a documented BPMN rule requires enforcement | `plans/591/PLAN-608.md` gap-3 addendum (`CONSIDER_MODEL_ORDER_STRATEGY` sweep) |
| 8 | Groups/annotations/associations do not distort primary control-flow layout | `ArtifactPlacement.kt`/`AssociationEdges.kt` rationale KDoc (this stage), `BpmnPlacementPassTest.kt` |
| 9 | All currently supported constructs remain valid/renderable, covered by deterministic golden regression | `ElkGoldenLayoutTest.kt` (24-fixture corpus) |
| 10 | Human-approved expected-output updates remain deterministic | `ElkGoldenLayoutTest.kt`'s `layout is deterministic across two runs`; ADRs in `plans/591/ARCHITECTURE.md` |
| 11 | Corpus covers long labels, multiple boundary events, cross-lane flows, nested subprocess exits, cycles, collaboration/message-flow | `long-labels`, `boundary-multi`, `collab-lanes`, `subprocess-nested`, `explicit-cycle`, `collab-*` fixtures |
| 12 | Output is structurally valid BPMN-DI and HITL-reviewed as readable in bpmn-js/demo.bpmn.io | `LayoutDiInspector`-based structural assertions in `ElkGoldenLayoutTest.kt`; HITL review record in `plans/591/ARCHITECTURE.md` |
| 13 | Every remaining non-ELK layout exception is documented with rationale, fixtures, and limited to projection | "Placement exception ledger" above (this stage) |
| 14 | Generated output satisfies BPMN semantic/DI interoperability for the retained profile | `BpmnLayoutPortCorpusIntegrationTest.kt` (real port + validation pipeline over the full corpus) |
| 15 | Validation corpus includes relevant BPMN MIWG interchange examples | `miwg-a2-1`, `miwg-a3-0` fixtures (this stage) |
| 16 | Layout profile encodes/tests the documented readability priority order | Geometry-invariant tests in `ElkGoldenLayoutTest.kt` (no-overlap, label-below-shape, minimal-bend assertions) |
| 17 | BPMN readability conventions are measurable objectives and HITL criteria, not arbitrary rejection rules | #606's "Rejected ELK-only lane-row experiment" ADR (`plans/591/ARCHITECTURE.md:78-80`); gap-3 addendum |

### Recommendation: a follow-up epic for complex MIWG interchange coverage

Cross-referencing the [BPMN MIWG test suite](https://github.com/bpmn-miwg/bpmn-miwg-test-suite)'s
`B.1.0`/`B.2.0` conformance-coverage references against this profile's vocabulary table
surfaces genuine, currently-unsupported territory: signal, escalation, and conditional
event definitions; non-interrupting boundary events (of any type — our engine only
represents `cancelActivity="true"`); link events; and data objects/stores/references with
their own routed `dataInputAssociation`/`dataOutputAssociation` edges. `B.2.0` alone is
also the single densest routing graph found in the reference suite (85 sequence flows, 8
gateways spanning all four types including one 4-way event-based gateway, 11 boundary
events, 5 subprocesses), making it a natural anchor for a dedicated stress-test corpus.
Combined with the multi-participant pool-stacking defect above, supporting these
reference diagrams properly is a genuine vocabulary-and-robustness expansion — explicitly
out of scope for epic #591 (whose non-goals exclude "expanding the supported BPMN
vocabulary beyond the currently supported profile") and for `#608` specifically. A new
epic should scope: (1) the four new event-definition types and non-interrupting boundary
events as first-class, tested constructs; (2) data objects/associations as a new routed
artifact category; (3) fixing `WhiteBoxPoolBandPlacement`/`CollaborationShapePlacement`
for 3+ participant collaborations; and (4) a `B.2.0`-derived dense-routing fixture once
(1)-(3) land.
