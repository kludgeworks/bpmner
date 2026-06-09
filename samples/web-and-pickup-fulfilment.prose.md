# Web order and store-pickup order fulfilment

The warehouse receives orders from two channels: web orders that need
shipping, and store-pickup orders that the customer will collect in
person. Both channels share the same warehouse picking-and-packing
sequence — it is the same workers, the same equipment, and the same
steps regardless of where the order came from. The shared sequence is
defined once and called from both flows.

The shared "fulfil order" sub-process is invoked with the order id and
the destination type (ship or pickup). Inside the sub-process, a picker
walks the floor and collects each line item using a handheld scanner,
a packer measures and boxes the items with appropriate packing
material, the packed parcel is weighed, and a label is printed and
applied. The sub-process completes once the labelled parcel is at the
outbound staging area for ship orders, or at the customer-collection
shelf for pickup orders. The sub-process returns the parcel id and the
final dimensions to the calling process.

For web orders the parent flow handles channel-specific steps before
and after: receiving the order from the website, checking payment
authorisation, calling the fulfil-order sub-process, then handing the
parcel to the shipping carrier and emailing the customer a tracking
number.

For store-pickup orders the parent flow is different: receiving the
order from the in-store kiosk, calling the same fulfil-order sub-process,
then notifying the customer by SMS that their order is ready, and
holding the parcel on the collection shelf for up to seven days before
returning unclaimed orders to inventory.
