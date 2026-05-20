# Nightly settlement reconciliation

Every night at 02:00 UTC the settlement reconciliation process runs against
the previous business day's trade ledger. The job is unattended — there is
no human trigger and no upstream message; it runs purely on a recurring
cycle defined by the operations calendar.

When the timer fires, the system pulls every settled trade from the trade
booking platform and compares it against the corresponding entries the
clearing house posted. Matched trades are flagged as reconciled and moved
to the closed-trades archive. Mismatched trades — where the booked amount,
counterparty, or settlement date differ — are written to a break log and
forwarded to the operations queue for manual review the next morning.

If the run completes with zero breaks, the process records the reconciled
batch and ends. If breaks were found, the process raises a summary
notification to the operations distribution list with the count and a link
to the break log, then ends.

The next run is scheduled by the same cycle timer regardless of the
previous outcome — there is no retry mechanism within a single night;
operations triage the breaks the following morning and a manual rerun
covers anything that needed an upstream fix.
