# ADR-011: Correct, represent, or terminate — the rule for fallible stage outcomes

No pipeline stage may emit an invalid artifact, and no failure may be a bare throw: every
fallible stage must **correct** the problem (a corrective retry loop with the prior failure fed
back into the prompt), **represent** it (a type that makes the failure structurally
unconstructible, so nothing downstream can be handed an invalid instance), or **terminate** with
a diagnosable, typed outcome the run reports.

The guarantee has **two** mechanisms, not one: typed terminal states the GOAP planner can carry
as an action's produced type, and the `BpmnRunAbortedEvent` backstop for failures the planner has
no typed state to carry. The backstop is the *primary* route for readiness failures, not a spare
mechanism to be tidied away once a typed terminal is added.

Readiness deliberately has no typed terminal: an action's GOAP effect is the type it produces,
and the planner's progress test (`nextState != currentState`) prunes an action that would not
change the world state. A caller-supplied `ProcessInputAssessment` relies on exactly this to skip
the readiness stage. Wrapping the readiness result in a typed outcome would give the planner a
new type to produce on every path, breaking that skip seam — so readiness stays on the backstop.

Origin: epic #685.
