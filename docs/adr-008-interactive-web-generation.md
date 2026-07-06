# ADR-008: Interactive web generation (no synchronous 422)

**ADR-8 option b:** Web-initiated generation runs in `INTERACTIVE` mode; clarifications surface
over SSE. There is no synchronous `422 Blocked` branch — the web path calls `startAsync(request)`
without a prior assessment handshake, and any need for clarification is resolved interactively
over the event stream.

Origin: epic #424 (option b decision). See `architecture.md` §5.
