# Loan application credit-tier routing

When the credit-check subprocess returns its score, the loan application
is routed to one of three underwriting paths based on the score.

If the applicant's credit score is 750 or above, the application is
routed to the fast-track underwriting queue — most documentation
requirements are waived, the application is reviewed within one business
day, and the offer rate is the standard published rate.

If the score is between 600 and 749, the application goes to standard
underwriting — full documentation is required, the review takes three
to five business days, and the offer rate is risk-adjusted based on the
score band.

For every other case — scores below 600, scores that came back as
"insufficient credit history", and any score the credit bureau could
not return — the application is routed to manual review by an
underwriter. The manual reviewer reads the full application, decides
whether to ask for additional documentation, and either offers a rate
or declines. This fallback is the catch-all: anything not explicitly
matched by the fast-track or standard conditions ends up here.

The three paths converge back at the offer-generation step, where the
application becomes either a written offer or a written decline letter.
