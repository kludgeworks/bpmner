export type RuleLevel = 'warn' | 'error';

export const BPMNER_PLUGIN_PACKAGE = 'bpmnlint-plugin-bpmner';
export const BPMNER_PLUGIN_PREFIX = 'bpmner';

export type CustomRuleManifestEntry = {
  id: string;
  level: RuleLevel;
};

export const customRuleManifest: CustomRuleManifestEntry[] = [
  { id: 'act-01-verb-object-name', level: 'warn' },
  { id: 'act-02-activity-label-capitalization', level: 'warn' },
  { id: 'act-03-discouraged-business-verbs', level: 'warn' },
  { id: 'act-12-loop-task-annotation', level: 'error' },
  { id: 'act-13-mi-task-annotation', level: 'error' },
  { id: 'assoc-01-required-annotation-association', level: 'error' },
  { id: 'data-01-no-type-words-in-data-name', level: 'error' },
  { id: 'evt-01-event-state-name', level: 'warn' },
  { id: 'evt-02-event-state-pattern', level: 'warn' },
  { id: 'evt-10-start-no-incoming', level: 'error' },
  { id: 'evt-11-message-start-has-message-flow', level: 'error' },
  { id: 'evt-13-intermediate-event-not-action', level: 'warn' },
  { id: 'evt-14-boundary-event-constraints', level: 'error' },
  { id: 'evt-15-error-end-boundary-pair', level: 'error' },
  { id: 'evt-16-link-event-pairing', level: 'error' },
  { id: 'flow-01-sequence-flow-within-pool', level: 'error' },
  { id: 'flow-02-diverging-flow-outcome-label', level: 'warn' },
  { id: 'gen-01-bpmnsubset-allowed-elements', level: 'error' },
  { id: 'gen-02-no-duplicate-diagrams', level: 'error' },
  { id: 'gtw-01-diverging-gateway-question', level: 'warn' },
  { id: 'gtw-02-converging-gateway-unnamed', level: 'warn' },
  { id: 'gtw-03-gateway-no-work-label', level: 'warn' },
  { id: 'gtw-11-event-based-direct-events', level: 'error' },
  { id: 'gtw-12-diverging-flow-names', level: 'error' },
  { id: 'gtw-20-no-gateway-join-fork', level: 'error' },
  { id: 'gtw-21-fake-join', level: 'error' },
  { id: 'gtw-22-superfluous-gateway', level: 'error' },
  { id: 'msg-01-message-flow-across-pools', level: 'error' },
  { id: 'msg-02-message-flow-name-pattern', level: 'warn' },
  { id: 'name-01-business-meaningful-label', level: 'warn' },
  { id: 'name-02-uncommon-abbreviations', level: 'warn' },
  { id: 'name-03-no-element-type-words', level: 'error' },
];

export const recommendedPluginRules = Object.fromEntries(
  customRuleManifest.map(({ id, level }) => [id, level])
) as Record<string, RuleLevel>;

export const allPluginRules = Object.fromEntries(
  customRuleManifest.map(({ id }) => [id, 'error'])
) as Record<string, RuleLevel>;

export const pluginRulePaths = Object.fromEntries(
  customRuleManifest.map(({ id }) => [id, `./rules/${id}`])
) as Record<string, string>;
