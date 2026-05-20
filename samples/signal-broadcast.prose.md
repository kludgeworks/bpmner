# Compliance rule update broadcast

The compliance team publishes a new rule revision through their internal
console. Publishing emits a `ComplianceRuleUpdated` signal that is
broadcast across the platform — it is not addressed to any single process
and any subscribed process catches it independently.

The signal carries the rule id, the new revision number, and the
effective-from timestamp. Three processes subscribe today: the
underwriting decisioning service, the customer-onboarding KYC checker,
and the audit-trail emitter. Each handles the signal on its own terms.

The underwriting decisioning service catches the signal and reloads its
rules cache from the rules service so that any decision evaluated after
the effective-from timestamp uses the new revision. The cache reload is
synchronous; failure to reload is logged but does not stop the signal
from being handled by the other subscribers.

The customer-onboarding KYC checker catches the signal and revalidates
any in-flight onboarding cases whose KYC determination depended on the
prior revision of the changed rule. Cases that flip from approved to
needs-review are routed to manual review with a note explaining the
rule revision.

The audit-trail emitter catches the signal and writes a structured audit
event recording the rule id, revision, effective-from timestamp, and the
publisher. The audit event is the system of record for compliance audits.

The signal-publishing process ends as soon as the signal is broadcast.
The compliance team does not wait for or track subscriber acknowledgement;
delivery is fire-and-forget by design.
