<!-- markdownlint-disable MD013 -->

# BPMN Profile

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

Topology and structural rules (gateway shape, dangling edges, naming conventions,
etc.) live in the rule catalog — see
[`linter/README.md`](../linter/README.md) and
[`linter/docs/rule-authoring-guide.md`](../linter/docs/rule-authoring-guide.md).
