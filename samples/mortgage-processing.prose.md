
# Mortgage application document processing

When a mortgage application is submitted, the system runs an
automated formatting script that normalises the applicant's address
into the canonical postal-service format, converts all currency
values into the application's submission currency, and computes the
derived loan-to-value ratio from the submitted property price and
loan amount. This script runs entirely inside the workflow engine
with no external service calls — it's just data manipulation against
the application object.

The normalised application is then passed to the credit-policy
decision service, which evaluates the bank's published credit-policy
rule set against the application data and returns an initial verdict:
auto-approve, auto-decline, or refer for human review. The rule set
is maintained as a decision table managed by the credit-policy team,
not as engine code.

For the auto-decline path, the system sends a decline notification
message to the customer's preferred contact channel — email or SMS,
depending on the customer's profile setting. The decline message is
sent and the workflow does not wait for any acknowledgement.

For the refer-for-human-review path, the application waits in the
underwriter's queue until the underwriter records their verdict
through the underwriting tool. While the application waits, the
workflow blocks for a customer-acknowledgement message: the customer
must confirm they have read the "your application is under manual
review" notification before the underwriter is allowed to start work.
The workflow does not proceed until that acknowledgement message is
received.

If the property has been flagged as a historical building, a heritage
consultant manually visits the property and confirms its condition
before underwriting can proceed. The consultant's visit is recorded
in the workflow but the system provides no tool for the visit itself
— the consultant works with paper notes and updates the workflow
afterwards.
