# ADR-010: Sanctioned architecture-test exceptions via opt-in marker annotation

Sanctioned exceptions to architecture rules are declared via `@SanctionedArchitectureException(reason = ...)` instead of brittle class-name pins inside `BpmnerArchitectureTest.kt`.

This opt-in marker satisfies the core constraint of ADR-007 Decision 2: it is not a blanket `ignoreDependency` filter (which would allow unchecked violations), but a self-declaring, audited, and strictly scoped exemption. Only classes annotated with `@SanctionedArchitectureException` are allowed to bypass the specified boundary.

Origin: epic #528 Stage 4c. See `../architecture.md` §6.
