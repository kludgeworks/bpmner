# gtw-22-superfluous-gateway

- Severity: error
- Purpose: Remove passthrough gateways that carry no routing decision, reducing diagram noise and preventing confusing single-path gateway shapes.
- Trigger: Exclusive, inclusive, or parallel gateway has exactly one incoming flow and exactly one outgoing flow.
