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
