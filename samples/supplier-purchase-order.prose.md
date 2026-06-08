# Supplier purchase order

The procurement workflow involves two separate organisations: the buying
company and the supplier.

On the buyer side, a buyer creates a purchase-order draft, has it
approved by their manager if it exceeds the buyer's authority limit, and
then issues the purchase order to the supplier by sending it through
the supplier's order-intake API.

On the supplier side, the order intake system receives the purchase
order, validates the line items against the supplier's catalogue,
generates an order acknowledgement with a committed delivery date, and
returns the acknowledgement to the buyer through a webhook callback.

Back on the buyer side, the buyer's purchasing system records the
acknowledged delivery date against the original purchase order, notifies
the requesting team that fulfilment is confirmed, and waits for the
goods-received notification from the warehouse before triggering payment.

Either party can cancel: if the buyer cancels before acknowledgement,
the supplier never sees the order; if the supplier rejects the order
during validation, an order-rejected message is sent back to the buyer
who notifies the requesting team that the order failed.
