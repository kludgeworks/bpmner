# Daily settlement broadcast

The financial settlement workflow runs once per day after market close.
After the day's trades are reconciled and the books closed, the workflow
broadcasts a "settlement complete" signal to every downstream system
that depends on the day's settled positions. The risk-engine, the
regulatory-reporting pipeline, the customer-statement generator, and
the next-day positioning system all subscribe to this signal and react
in their own ways.

This is not a targeted message to one recipient — it is a one-to-many
broadcast that anyone subscribing to it will receive. The settlement
workflow itself ends with that broadcast; there is no further work to
do. The broadcast IS the completion: signalling that today's books are
settled and downstream consumers may proceed.
