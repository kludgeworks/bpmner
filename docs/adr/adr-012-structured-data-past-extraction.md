# ADR-012: Prose at the boundary, domain language within

Prose is the input form, and it stops at extraction. Past that boundary, structured domain data
reaches an LLM as **generated serialisation**, never as a hand-written projection into prose.
`assess_readiness` and `extract_contract` legitimately take raw prose — there is no domain
structure yet, and converting it is extraction's entire job. Every stage downstream of it
already holds a validated domain object, and re-flattening that object into markdown for a
prompt discards structure an earlier LLM call was paid to produce.

State the rule as *generated serialisation, never hand-written projection* — not as "use JSON" or
"pass the domain object". Passing a domain object straight into a Jinja template and iterating its
properties looks aligned with this rule and rebuilds the identical lossy, hand-maintained
projection in an untyped file outside the compiler and detekt. The property that kills the defect
class is that a serialiser walks every field and cannot forget one; the wire format is incidental.

Repair is not an instance of this rule: it consumes the real rendered XML plus diagnostics, not a
projection of a structured object, and stays untouched.

A hand-written markdown renderer may still exist for a human reader — an operator-facing log
line, for example — because its lossiness is harmless there. It must not also be the input an LLM
prompt is built from; those are two different consumers with two different requirements, and only
one of them tolerates loss.

Origin: epic #685 (Stage C, issue #690).
