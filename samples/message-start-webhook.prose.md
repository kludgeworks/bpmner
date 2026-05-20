# Inventory restock from supplier webhook

A supplier integration posts a JSON webhook to our inventory service each
time one of their SKUs is restocked at their distribution centre. Each
webhook carries the supplier id, the SKU, the new on-hand quantity at the
supplier, and the timestamp of the restock event.

The arrival of the webhook starts the restock-handling process. There is
no schedule and no manual trigger — the process exists solely to react to
the inbound message. The message is named `SupplierRestockNotification` in
the catalogue and is the only way this process can be initiated.

When the message arrives, the system validates the payload (supplier id is
known, SKU exists in our catalogue, quantity is non-negative). If validation
fails, the message is dead-lettered for ops review and the process ends.

For valid messages, the system updates the in-house availability cache,
recalculates the buyable quantity for the SKU across all sales channels,
and republishes the SKU on any channel where it had been hidden due to
zero availability. If any customer had placed a backorder for the SKU
while it was out of stock, the system also issues an `OrderReleased`
signal so the order-fulfilment process can pick those orders up.

The process ends once the cache update is acknowledged and any signal has
been emitted. A second webhook for the same SKU starts a fresh process
instance — there is no in-process state shared between restock events.
