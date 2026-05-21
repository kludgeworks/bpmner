# Overdue approval escalation

The capital-expenditure approval workflow routes proposals through a
chain of approvers — submitter, line manager, departmental director,
and finance controller — each of whom has three business days to act.
Most proposals move through quickly; the workflow's happy path ends
when the finance controller approves and the proposal is logged for
budgeting.

However, when an approver has been sitting on the proposal for more
than the agreed window without acting, the workflow does NOT abandon
the proposal as failed. Instead it raises an "approval overdue"
escalation that propagates up to the proposal owner's office manager,
who follows up with the approver out-of-band. The proposal itself
remains valid and the approver can still act on it; the escalation
just adds the case to the office manager's follow-up dashboard.

The workflow ends with that escalation — it has done what it can. The
office manager's intervention is tracked separately. This is distinct
from an error: nothing has broken, the proposal just needs nudging.
