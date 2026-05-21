# Underwriting Error End

The loan underwriting workflow starts when an applicant submits a completed credit application.

The system records the application, retrieves the applicant credit profile, and calculates the underwriting score. The underwriter then decides whether the application meets the minimum credit policy.

If the applicant meets the credit policy, the underwriter approves the application and the workflow ends normally as approved.

If the applicant fails the credit policy, underwriting rejects the application and raises the `CREDIT_REJECTED` error from the end state so the calling loan-origination process can handle the failure.
