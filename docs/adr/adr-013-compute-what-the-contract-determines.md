# ADR-013: Compute what the contract determines; conserve it across retries

Two rules, R3 and R5, close the last defect class the epic named: a generation pass that
*recomputes* an attribute the contract already fixed, instead of accepting the model's guess or
losing the value across a corrective retry.

**R3 — compute what the input determines.** When a BPMN attribute has exactly one legal value
given the contract, that value is stamped deterministically rather than requested from the model
and validated afterwards. The operative test is: *would we overwrite the model's answer
unconditionally?* If yes, the attribute is determined and belongs in the conformance pass, not the
prompt. A stamp never rejects — the contract's value always wins (ADR-685-22) — so the pass cannot
itself fail the run; it can only make a downstream check pass that would otherwise have fired.

Unlike R2 (prose at the boundary — ADR-012), R3 is not quotable from the Embabel corpus. Its
warrant is empirical: two live runs of the generator, one of which reproducibly dropped
`isDefault` and boundary-timeout modifiers the contract had already fixed, and the pre-existing
`DefaultFlowAssigner`'s own comment recording the same failure mode for one field. R3 generalises
that observation to every field with the same shape, not a doctrinal position argued in advance.

**R5 — conserve across retries.** A corrective retry is licensed to change what the driving
diagnostic named; it is not licensed to silently drop anything else. Both the contract-extraction
loop and the outline-generation loop now reject an attempt that removes an element, or empties a
populated modifier field, that the previous attempt had and the current diagnostic did not point
at. The rejection feeds back as a new corrective diagnostic and consumes a retry attempt; it does
not throw and adds no `BpmnGenerationStatus` member — conservation exhaustion still exits through
the existing `CONTRACT_FAILED` / `OUTLINE_FAILED` terminals.

The outline-side check is scoped to *contract-realised* node ids only (the unified-id convention).
Synthesised routing nodes and every edge are excluded deliberately: their shape is the model's own
topology call, the genuinely underdetermined part of the job, and comparing them would reject
every legitimate restructuring retry within the first week of use.

## The conformance pass stamps seven attributes, not eight

`ACTIVITY_TASK_KIND_MISMATCH` was provisionally listed among the attribute stamps. It is not one.
The distinction that decides it, refined from ADR-685-21's own criterion:

> A subtype substitution is an **attribute stamp** when the source and target shapes are equal,
> and **structural synthesis** when they are not.

Gateway-kind substitution (stamp 7) passes that test: `BpmnExclusiveGateway`,
`BpmnInclusiveGateway`, `BpmnParallelGateway`, and `BpmnEventBasedGateway` share the identical
shape `(id, name, parentRef)`, so substituting one for another is total and loses nothing.
Task-kind substitution fails it: four of the nine contract activity kinds have no reachable target
shape (`Send`/`Receive` need a message-catalogue entry the contract does not carry; `CallActivity`
and `SubProcess` are structural), and `BpmnCallActivity`/`BpmnSubProcess` implement `BpmnNode` only,
not `BpmnTask` — substituting a task into either deletes the `multiInstance`/`standardLoop` fields
the iteration and loop stamps exist to write. `ACTIVITY_TASK_KIND_MISMATCH` therefore moves to
ADR-685-21's structural row; the bucket is **7 stamped / 6 structural**, still 13, still exhaustive.
The next architecture refresh should carry this into ADR-685-21's table directly.

The seven stamps: default-branch edge (`isDefault`, condition cleared), every branch's edge label
(`edge.name = branch.label` — the single cheapest correction here, since the generation prompt
never asked for it), task `multiInstance`, task `standardLoop`, end-event `eventDefinition`,
intermediate-throw `eventDefinition`, and gateway-kind substitution. The end-event and
intermediate-throw stamps are best-effort: they can only resolve an `errorRef`/`messageRef` against
a catalogue entry the model already emitted, never invent one — inventing a catalogue entry is
structural synthesis, out of scope by the same test that excludes task-kind substitution.

## Three corrections found while implementing

- **`BpmnFidelityIssue` has no `targetId`.** It carries `contractElementId` and `bpmnElementId`
  instead. The outline-side conservation check's `named` set is their union, not a `targetId`
  read.
- **`DefaultFlowAssigner` had three call sites, not two**: `LlmBpmnProcessGenerator`'s
  `attemptOutline`, in addition to both `BpmnRepairAdvancer` sites. It already ran before the
  fidelity check, so demoting the superseded fidelity checks to invariant assertions over the
  post-conformance artifact needed no reordering — the structure was already correct.
- **`DATA_REF_NOT_IN_ARTIFACTS` is deleted.** It was referenced nowhere but its own declaration:
  `ProcessContract` has no artifacts and no data references, so the code validated a model
  attribute the contract cannot express. Implementing it would have meant inventing the model it
  validates first, which is out of scope.

Origin: epic #685 (Stage D, issue #691).
