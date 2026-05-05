# KLM Rule Documentation

This folder documents each custom rule implemented by `bpmnlint-plugin-klm`.

## Phase 1 (deterministic, error)
- gen-01-klops-allowed-elements
- act-12-loop-task-annotation
- act-13-mi-task-annotation
- evt-10-start-no-incoming
- evt-11-message-start-has-message-flow
- evt-14-boundary-event-constraints
- evt-15-error-end-boundary-pair
- evt-16-link-event-pairing
- gtw-11-event-based-direct-events
- gtw-12-diverging-flow-names
- flow-01-sequence-flow-within-pool
- msg-01-message-flow-across-pools
- assoc-01-required-annotation-association
- data-01-no-type-words-in-data-name
- name-03-no-element-type-words

## Phase 2 (heuristic, warn)
- act-01-verb-object-name
- gtw-01-diverging-gateway-question
- msg-02-message-flow-name-pattern
