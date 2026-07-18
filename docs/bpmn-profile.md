<!-- markdownlint-disable MD013 -->

# BPMN Profile and Topology Policy

`bpmner` aims to support as much of the BPMN 2.0 spec as possible. For the initial
ELK layout migration (epic [#557](https://github.com/kludgeworks/bpmner/issues/557))
we implemented the highest-value subset first. This page tracks current support.

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

Removed constructs are not silently dropped: the parser routes anything it doesn't
recognize to an explicit rejection type (`BpmnUnrecognizedNode` /
`BpmnUnrecognizedEventDefinition`, `BpmnDomain.kt`) and generation fails with a typed
`UNFIXABLE` diagnostic rather than partially rendering. Deferred re-entry for these is
tracked by [#558](https://github.com/kludgeworks/bpmner/issues/558),
[#559](https://github.com/kludgeworks/bpmner/issues/559), and
[#560](https://github.com/kludgeworks/bpmner/issues/560).

## Topology policy

| Rule | Status | Enforcement |
| --- | --- | --- |
| Implicit split (branch without an explicit diverging gateway) | 🔴 | Pkl rule **"No Implicit Split"** (`gwNoImplicitSplit`), backed by `TopologyCheck.NO_IMPLICIT_SPLIT` |
| Fake join (convergence without an explicit converging gateway) | 🔴 | Pkl rule **"Fake Join"** (`gwFakeJoin`, auto-repaired), backed by `TopologyCheck.NO_FAKE_JOIN` |
| Gateway simultaneously join and fork | 🔴 (auto-repaired) | Pkl rule **"No Gateway Join Fork"** (`gwNoGatewayJoinFork`); `SplitJoinForkGatewayHandler` splits it into separate converging and diverging gateways |
| Disconnected ordinary flow-node components | 🔴 | `BpmnDefinitionValidator.kt:145-148` |
| Cyclic sequence flows and loop-backs | 🟢 | No cycle-rejection code exists; `BpmnToElkMapper.findLoopBackEdges` treats a cycle as a routing case |
| Diverging gateway without a matching converging gateway | 🟢 | No matching rule exists — absence is the policy |
| Message-flow density | 🟢 (no limit) | No message-flow-count rule exists |

Two related Pkl rules exist beside the policy above: **"Superfluous Gateway"** flags a
pass-through gateway with exactly one incoming and one outgoing flow, and
**"Converging Gateway Unnamed"**/**"Diverging Flow Names"** are naming conventions,
not topology-validity rules.
