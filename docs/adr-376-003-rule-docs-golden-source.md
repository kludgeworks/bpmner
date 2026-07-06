# ADR-376-003: Rule-docs golden source is the live bean catalog

The live bean metadata (`RuleMetadata`) is the **authoritative** golden source for rule
documentation and counts. Stale issue prose (e.g. a hardcoded "54 rules" count) does not
override the catalog; `RuleDocsGoldenTest` asserts the generated docs match the live
`RuleMetadata` beans, not a static copy of old Pkl output.

Origin: epic #376 (re-count from live catalog, applied #378, re-confirmed #379). See `architecture.md` §5.
