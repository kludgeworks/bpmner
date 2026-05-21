# Shipment Message End

The shipment workflow starts when a warehouse receives a paid order.

The warehouse picks the items, packs the order, buys a shipping label, and hands the parcel to the carrier.

After carrier handoff, the system records the tracking number and ends the workflow by sending a `Shipment confirmed` completion message to the storefront. The message payload includes the order id, carrier name, tracking number, and handoff timestamp.
