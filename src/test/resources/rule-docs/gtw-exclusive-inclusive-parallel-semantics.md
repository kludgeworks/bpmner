---
markdownlint-disable: MD013
---

# gtw-exclusive-inclusive-parallel-semantics

- **Name**: Exclusive Inclusive Parallel Semantics
- **Category**: Gateway
- **Severity**: WARNING
- **Target Elements**: `bpmn:ExclusiveGateway`, `bpmn:InclusiveGateway`, `bpmn:ParallelGateway`

## Intent

Keep gateway type choices aligned with BPMN token semantics.

## Modeller Guidance

Use exclusive gateways for exactly one path, inclusive gateways for one or more paths, and parallel gateways when all paths proceed together.

## AI Guidance

Enforce deterministic parallel-gateway structure from XML. Treat XOR versus OR versus AND selection as a modelling-intent decision unless explicit structural evidence makes it invalid.

## Diagnostic Messages

- `default`: Gateway semantics should match exclusive, inclusive, or parallel behavior
- `parallelCondition`: Parallel gateway outgoing sequence flow must not be conditional or default-only
- `parallelJoinCardinality`: Parallel converging gateway should have at least two incoming sequence flows
- `parallelSplitCardinality`: Parallel diverging gateway should have at least two outgoing sequence flows

## Repair

- **Kind**: `LLM_MODEL_PATCH`
- **Safety**: `LLM_ONLY`
