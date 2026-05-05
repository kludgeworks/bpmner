const plugin = {
  configs: {
    recommended: {
      extends: 'bpmnlint:recommended',
      rules: {
        'gen-01-klops-allowed-elements': 'error',
        'act-12-loop-task-annotation': 'error',
        'act-13-mi-task-annotation': 'error',
        'evt-10-start-no-incoming': 'error',
        'evt-11-message-start-has-message-flow': 'error',
        'evt-14-boundary-event-constraints': 'error',
        'evt-15-error-end-boundary-pair': 'error',
        'evt-16-link-event-pairing': 'error',
        'gtw-11-event-based-direct-events': 'error',
        'gtw-12-diverging-flow-names': 'error',
        'flow-01-sequence-flow-within-pool': 'error',
        'msg-01-message-flow-across-pools': 'error',
        'assoc-01-required-annotation-association': 'error',
        'data-01-no-type-words-in-data-name': 'error',
        'name-03-no-element-type-words': 'error',
        'act-01-verb-object-name': 'warn',
        'gtw-01-diverging-gateway-question': 'warn',
        'msg-02-message-flow-name-pattern': 'warn'
      }
    }
  },
  rules: {
    'gen-01-klops-allowed-elements': './rules/gen-01-klops-allowed-elements',
    'act-12-loop-task-annotation': './rules/act-12-loop-task-annotation',
    'act-13-mi-task-annotation': './rules/act-13-mi-task-annotation',
    'evt-10-start-no-incoming': './rules/evt-10-start-no-incoming',
    'evt-11-message-start-has-message-flow': './rules/evt-11-message-start-has-message-flow',
    'evt-14-boundary-event-constraints': './rules/evt-14-boundary-event-constraints',
    'evt-15-error-end-boundary-pair': './rules/evt-15-error-end-boundary-pair',
    'evt-16-link-event-pairing': './rules/evt-16-link-event-pairing',
    'gtw-11-event-based-direct-events': './rules/gtw-11-event-based-direct-events',
    'gtw-12-diverging-flow-names': './rules/gtw-12-diverging-flow-names',
    'flow-01-sequence-flow-within-pool': './rules/flow-01-sequence-flow-within-pool',
    'msg-01-message-flow-across-pools': './rules/msg-01-message-flow-across-pools',
    'assoc-01-required-annotation-association': './rules/assoc-01-required-annotation-association',
    'data-01-no-type-words-in-data-name': './rules/data-01-no-type-words-in-data-name',
    'name-03-no-element-type-words': './rules/name-03-no-element-type-words',
    'act-01-verb-object-name': './rules/act-01-verb-object-name',
    'gtw-01-diverging-gateway-question': './rules/gtw-01-diverging-gateway-question',
    'msg-02-message-flow-name-pattern': './rules/msg-02-message-flow-name-pattern'
  }
};

export = plugin;