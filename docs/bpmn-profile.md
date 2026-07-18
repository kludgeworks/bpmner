<!-- markdownlint-disable MD013 -->

# BPMN Profile and Topology Policy

`bpmner` supports a deliberately constrained BPMN 2.0 vocabulary and topology, chosen
for high-value coverage over completeness (epic [#557](https://github.com/kludgeworks/bpmner/issues/557)).
This document is the authoritative reference for what is retained, what is rejected,
and where each rule is enforced in code. See [`architecture.md`](architecture.md) and
[ADR-011](adr/adr-011-jvm-native-bpmn-layout.md) for the layout-engine design that
motivated the contraction.

## Retained profile

The supported vocabulary is defined structurally by two sealed interfaces in
[`BpmnDomain.kt`](../src/main/kotlin/dev/groknull/bpmner/bpmn/BpmnDomain.kt) — there is
no separate vocabulary registry to keep in sync; the sealed subtype list *is* the
profile:

- **`BpmnNode`** (`BpmnDomain.kt:217`) — `BpmnStartEvent`, `BpmnEndEvent`,
  `BpmnIntermediateEvent`, `BpmnBoundaryEvent`; task kinds `BpmnTask`, `BpmnUserTask`,
  `BpmnServiceTask`, `BpmnScriptTask`, `BpmnBusinessRuleTask`, `BpmnSendTask`,
  `BpmnReceiveTask`; `BpmnCallActivity`; `BpmnSubProcess` (embedded subprocess);
  `BpmnParallelGateway`, `BpmnExclusiveGateway`, `BpmnInclusiveGateway`,
  `BpmnEventBasedGateway`; `BpmnLane`/`BpmnLaneSet`.
- **`BpmnEventDefinition`** (`BpmnDomain.kt:153`) — `BpmnNoneEventDefinition`,
  `BpmnTimerEventDefinition`, `BpmnMessageEventDefinition`, `BpmnErrorEventDefinition`,
  `BpmnTerminateEventDefinition`.
- Participants, pools, black-box participants, and ordinary cross-pool message flows
  (collaboration-level, see `CollaborationShapePlacement`/`MessageFlowEdges`).
- Text annotations and ordinary associations.
- Standard-loop and multi-instance activity characteristics.
- Cyclic sequence flows and loop-back paths (see Topology policy below).

## Removed BPMN capabilities

Event subprocesses, compensation events/handlers/associations, escalation events,
signal events, data objects/stores/references, data input/output associations, and
non-interrupting boundary-event variants are **not** supported. There is no
`BpmnEventSubprocess`, `BpmnCompensation*`, `BpmnDataObject*`, or `BpmnDataStore*`
type, and no `Signal`/`Escalation`/`Compensation` `BpmnEventDefinition` subtype — the
removal is structural, not a generator-prompt omission. These are deliberate scope
decisions for the ELK migration, not judgments that the constructs lack value; see
[Deferred capabilities](#deferred-capabilities) below.

## Rejected-construct examples

Removed constructs do not silently disappear. The parser routes anything it doesn't
recognize into an explicit rejection type — `BpmnUnrecognizedNode` or
`BpmnUnrecognizedEventDefinition` (`BpmnDomain.kt:182`, `:624`) — rather than dropping
it from the parsed tree. Before any generation attempt is serialized,
`BpmnRepairAdvancer.initialEvaluation` runs `BpmnUnrecognizedElementScanner.scan` as a
pre-flight step (`BpmnRepairAdvancer.kt:51-58`) and short-circuits to a typed
`UNFIXABLE` diagnostic if anything unrecognized is found. The `BpmnSubset` rule flags
`BpmnUnrecognizedEventDefinition` at the ruleset layer too
(`BpmnDefinitionValidator.kt:393-394`). Test evidence:
`BpmnXmlToDefinitionConverterTest.kt`, `` `parse surfaces removed signal event
definitions as unsupported` ``.

Two representative inputs and their outcome:

```xml
<!-- Removed: signal events -->
<bpmn:intermediateThrowEvent id="Signal_1">
  <bpmn:signalEventDefinition signalRef="Signal_A" />
</bpmn:intermediateThrowEvent>
```

Parses to a node whose event definition is `BpmnUnrecognizedEventDefinition`; the
pre-flight scan short-circuits generation with an `UNFIXABLE` diagnostic naming the
element.

```xml
<!-- Removed: event subprocesses -->
<bpmn:subProcess id="EventSub_1" triggeredByEvent="true">
  ...
</bpmn:subProcess>
```

No `BpmnEventSubprocess` type exists to represent this; the element parses to
`BpmnUnrecognizedNode` and is rejected the same way — never partially rendered.

## Topology policy

The seven rules below (from issue [#557](https://github.com/kludgeworks/bpmner/issues/557)'s
"Topology policy" section) are enforced by several independent, existing mechanisms —
there is no single `TopologyPolicy` class; each rule is named and linked to its
enforcing code here rather than summarized behind a new abstraction:

| Rule | Enforcement |
| --- | --- |
| Reject implicit splits — branching must use an explicit diverging gateway | Pkl rule **"No Implicit Split"** (`gwNoImplicitSplit`, ERROR, targets `bpmn:FlowNode`), backed by `TopologyCheck.NO_IMPLICIT_SPLIT` (`TopologyCheck.kt:61-71`) — flags a non-gateway node with more than one outgoing sequence flow |
| Reject fake joins — convergence must use an explicit converging gateway | Pkl rule **"Fake Join"** (`gwFakeJoin`, ERROR, auto-repaired by `insertConvergingGateway`), backed by `TopologyCheck.NO_FAKE_JOIN` (`TopologyCheck.kt:43-59`) — flags a task with 2+ incoming flows where none originates from an explicit converging gateway |
| Reject a gateway that is simultaneously join and fork | Pkl rule **"No Gateway Join Fork"** (`gwNoGatewayJoinFork`, ERROR), backed by `TopologyCheck.NO_JOIN_FORK` (incoming ≥ 2 and outgoing ≥ 2); auto-repaired by `SplitJoinForkGatewayHandler`, which splits the combined gateway into separate converging and diverging gateways (`SplitJoinForkGatewayHandler.kt:20-40`, paired with `InsertConvergingGatewayHandler` and `JoinGatewayKindSelector`) |
| Reject disconnected ordinary flow-node components | `BpmnDefinitionValidator.kt:145-148` — `"$scope contains disconnected flow nodes: ..."` |
| Permit semantically valid cycles and loop-backs | No cycle-rejection code exists; ELK's `BpmnToElkMapper.findLoopBackEdges` treats a cycle as a routing case (`LoopBackEdgeArcsTest`, `collab-lanes-loopback` fixture), not a validation error |
| Do not require every diverging gateway to have a matching converging gateway | No such matching rule exists anywhere in `BpmnDefinitionValidator`, `TopologyCheck`, or the rest of the ruleset — absence is the policy |
| No arbitrary message-flow density limit; no invented layout-driven validity restriction | No message-flow-count rule exists; ELK renders collaboration edges best-effort (AD-557-04, `plans/557/ARCHITECTURE.md`) rather than bpmner imposing a layout-driven cap |

Two related Pkl rules exist beside the seven above but are not part of the #557 policy
text: **"Superfluous Gateway"** (`gwSuperfluousGateway`, `TopologyCheck.NO_SUPERFLUOUS`)
flags a pass-through gateway with exactly one incoming and one outgoing flow, and
**"Converging Gateway Unnamed"**/**"Diverging Flow Names"** are naming conventions, not
topology-validity rules.

## JVM dependency and runtime change

Layout no longer runs GraalJS/Polyglot hosting the `yet-another-bpmn-auto-layout` npm
bundle. The engine is a single JVM dependency,
`org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0` (EPL-2.0; full closure recorded in
[`third-party-licenses.md`](third-party-licenses.md)), invoked in-process and
synchronously by `ElkBpmnLayouter` (`@InfrastructureRing @Service`, the sole
`BpmnLayoutPort` adapter). There is no JS bundle, no esbuild/pnpm packaging step for
layout, no Promise-to-JVM bridge, and no timeout/cancellation machinery — a layout call
is an ordinary synchronous method call that either returns laid-out XML or throws.

## Deterministic layout contract

Per invocation, the ELK graph and layout-engine state are created fresh: there is no
shared context or mutable process-global layout state. A fixed non-zero random seed and
stable element/port insertion order (document order) mean identical semantic input and
configuration produce byte-stable BPMN-DI ordering and numerically stable coordinates
(`plans/557/ARCHITECTURE.md` AD-557-03). `ElkGoldenLayoutTest` enforces this byte-exact
against the 22-fixture corpus.

## Single-authority snapshot behavior

The JVM ELK engine is the sole producer of layout geometry for both the final result
and live browser rendering — there is no second layout authority. `snapshot-import.ts`
has no `layoutProcess`/client-side auto-layout call (AD-557-06,
`plans/557/ARCHITECTURE.md`): a DI-bearing snapshot is imported as-is; a DI-less
intermediate snapshot resolves `pending` immediately and the last authoritative diagram
stays on screen. `LAYOUT_COMPLETE` remains the one authoritative drawing event.

## Rendering validation: the JVM-native structural check

Rather than a JS-import-throw assertion or any jsdom/Playwright harness,
`BpmnLayoutAgent.validateFinalBpmnXml` runs a permanent, JVM-native referential-
integrity check on every production layout call: DI-to-semantic uniqueness/resolution,
edge endpoint resolution, and boundary-host attachment
(`layout/internal/adapter/inbound/BpmnLayoutAgent.kt`, `referentialIntegrityErrors`).
This supersedes the "does it import into bpmn-js without throwing" check — verified
against `bpmn-js` source, that importer demotes almost every referential-integrity
failure to a non-throwing warning, so it was never the rigorous oracle it was assumed
to be (AD-557-17, `plans/557/ARCHITECTURE.md`). No JS/Node/jsdom/Playwright harness is
added for this or any other rendering-validation purpose.

## Extending the rendering corpus

`src/test/resources/layout-fixtures/*.bpmn` / `*.expected.bpmn` hold 22 approved pairs,
run byte-exact by `ElkGoldenLayoutTest`. Adding a new fixture is a human-in-the-loop
workflow, not an automated generation step:

1. Add one diagram at a time, simplest geometry first.
2. Generate its layout and present it in bpmn-js for human review.
3. Iterate until the reviewer is satisfied with the rendered result.
4. Freeze and commit that golden before advancing to the next diagram.

**Cross-diagram regression rule:** if a new diagram regresses an already-approved one,
fix both — never re-bless the regressed fixture just to silence the suite.

## Deferred capabilities

Constructs removed by #557 are recorded as deferred candidates for future work, each
gated on a specific layout-engine prerequisite:

- [#558](https://github.com/kludgeworks/bpmner/issues/558) — signal, escalation,
  compensation, and non-interrupting boundary-event variants. Prerequisite: restore
  only after the JVM-native layout supports their event and boundary attachment
  geometry.
- [#559](https://github.com/kludgeworks/bpmner/issues/559) — event subprocesses.
  Prerequisite: restore only after the JVM-native layout supports event-triggered
  subprocess containment and its required BPMN-DI geometry.
- [#560](https://github.com/kludgeworks/bpmner/issues/560) — data objects, stores,
  references, and data associations. Prerequisite: restore only after the JVM-native
  layout supports data-artifact and association geometry.
</content>
