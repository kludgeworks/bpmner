# gtw-20-no-gateway-join-fork

- Severity: error
- Purpose: Prevent a single gateway element from acting as both a converging join and a diverging fork, which is ambiguous and unsupported in most engines.
- Trigger: Exclusive, inclusive, or parallel gateway has two or more incoming flows AND two or more outgoing flows.
