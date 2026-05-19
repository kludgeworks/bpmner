# E-commerce order fulfillment

When a customer places an order online, the order enters the warehouse
processing queue. From there it goes through a standard fulfillment
sequence before being shipped.

The fulfillment sequence is the same for every order regardless of how it
arrived — web orders, partner-channel orders, and in-store pickups all hand
off to the same flow. The sequence is: a picker walks the floor with a
handheld scanner and collects each item in the order from its bin; once
every item is collected, a packer measures the items, chooses an
appropriately sized box, arranges the items inside, adds packing material,
and seals the box; the packed parcel is then weighed, a shipping label is
printed and applied, and the labelled parcel is moved to the outbound
staging area. Because this pick-pack-label sequence is reused across every
order channel, the warehouse treats it as a single named subprocess.

A shipping carrier collects the staged parcels at end of day. Once the
parcel has been handed off, the order management system marks the order as
shipped and emails the customer with the tracking number.

If the picker cannot find a particular item (it is missing from its bin or
the stock count was wrong), the order is removed from the queue and routed
to the inventory team for reconciliation; the customer is notified that
the order is delayed.
