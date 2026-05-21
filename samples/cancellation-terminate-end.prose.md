# Customer Cancellation With Terminate End

The booking workflow starts when a customer submits a vacation booking request.

The agency opens three preparation tracks in parallel: reserve the flight, reserve the hotel, and reserve the rental car. While those tracks are running, the customer may cancel the booking.

If the cancellation is received before the booking is confirmed, the workflow terminates immediately and abandons all in-flight reservation work. No remaining booking track should continue after this cancellation end state.

If the customer does not cancel, all reservation tracks complete, the agent confirms availability, and the workflow ends with the booking confirmed.
