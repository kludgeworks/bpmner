# Customer support ticket triage

Customers raise support tickets through a web form. When a ticket lands in
the queue, the next available agent picks it up.

The agent reads the ticket, records their initial diagnosis, and decides
between two paths. They either mark the ticket resolved with a written
resolution, or they send a clarifying question back to the customer and wait
for the reply.

If a clarifying question was sent, the agent must wait for the customer's
response before proceeding. Once the reply arrives, the agent reads the
updated thread and decides again — resolve, or send another clarifying
question. This back-and-forth continues until one of two things happens.
Either the agent records a resolution, in which case the ticket is closed
and an automated thank-you email is sent to the customer. Or the loop
reaches three clarifying-question rounds without resolution, at which point
the ticket is escalated to the team lead with the full conversation log
attached, and the agent is freed to take the next ticket from the queue.

Resolved tickets and escalated tickets are tracked separately so the team
can review escalation rates weekly.
