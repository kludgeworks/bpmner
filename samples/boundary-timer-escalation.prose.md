# Approval-task SLA escalation

When a purchase request enters the approval workflow it is routed to the
applicable approver as a user task. The approver is expected to either
approve or decline the request within 24 hours of the task landing in
their queue.

The 24-hour window is enforced by an interrupting timer that runs on the
approval task itself. If the approver acts before the timer fires, the
process continues normally — approved requests flow to the procurement
team for ordering, declined requests are returned to the requester with
the approver's reason note.

If the timer fires before the approver acts, the in-flight approval task
is cancelled and the process escalates the request to the approver's
manager. The escalation creates a new approval task addressed to the
manager with a note explaining that the original 24-hour SLA was missed.
The original approver receives a notification that the escalation
happened.

The manager's task has its own 24-hour timer with the same interrupting
behaviour. If the manager also misses the SLA, the request is escalated
one more level to the department head; if the department head also
misses, the request is auto-declined with an SLA-breach reason and the
requester is notified.

The escalation chain stops at the department head — there is no further
auto-escalation beyond three levels. The process ends when an approver
at any level acts on the request, when the auto-decline triggers at
the third missed SLA, or when the requester withdraws the request
manually before any of those terminal events.
