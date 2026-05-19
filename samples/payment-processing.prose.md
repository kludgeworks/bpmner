# Online payment processing

When a customer reaches the payment step at checkout, the payment service
sends a charge request to the upstream payment processor and then waits for
the processor to respond.

The wait ends in one of three ways. If a success confirmation arrives, the
order is marked paid, the cart is converted to a confirmed order, and a
receipt email is sent to the customer. If a decline notification arrives,
the order is held in a "payment failed" state and the customer is shown an
in-page retry prompt offering them a different card; the customer may retry
the charge up to three times, and on the fourth decline the order is
abandoned and the cart cleared. If no response of any kind arrives within
sixty seconds of the original charge request, the system treats the
attempt as timed out, cancels the in-flight charge request, and routes the
order to manual review for a customer service agent to follow up.

Confirmed-paid orders are immediately handed off to the fulfillment queue.
Failed or abandoned orders remain in the system as lost-cart records for
thirty days so that marketing can send a recovery email; after the thirty
days have elapsed the cart is deleted. Manually-reviewed orders are picked
up by a customer service agent who phones the customer and either
re-runs the charge over the phone or cancels the order with the customer's
agreement.

If at any point during the wait the processor responds with a
network-error code (rather than a success or decline), the system retries
the charge request once more before treating the attempt as timed out. The
retry counts against the three-strike retry budget the customer also has,
so a single charge attempt by the customer may translate into multiple
attempts to the processor.
