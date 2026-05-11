import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import BpmnModdle from 'bpmn-moddle';
import { Linter } from 'bpmnlint';
import { configs, customRuleDocs, resolver } from '../src/generated/static-rules';
import { getBpmnlintLevel, getRuleConfig, getRuleMessage, getStaticConfig } from '../src/rule-config';
import catalog from '../src/generated/linter-rules.json';
import { fixtures } from './fixtures';

const BPMNER_PLUGIN_PREFIX = 'bpmner';

type Report = {
  id?: string;
  message: string;
  category?: string;
};

type LintResults = Record<string, Report[]>;

type RuleCatalog = {
  rules: Array<{ id: string; hasTsImplementation: boolean }>;
};

class PluginResolver {
  resolveRule = resolver.resolveRule;
  resolveConfig = resolver.resolveConfig;
}

const customRuleConfig = {
  extends: ['plugin:bpmner/recommended'],
};

const customRuleConfigAll = {
  extends: ['plugin:bpmner/all'],
};

async function lint(xml: string): Promise<LintResults> {
  return lintWithConfig(xml, customRuleConfig);
}

async function lintWithConfig(xml: string, config: unknown): Promise<LintResults> {
  const moddle = new BpmnModdle();
  const { rootElement } = await moddle.fromXML(xml);
  const linter = new Linter({
    config,
    resolver: new PluginResolver(),
  });
  return linter.lint(rootElement as never) as LintResults;
}

function reportsFor(results: LintResults, ruleName: string): Report[] {
  const match = Object.entries(results).find(([key]) => {
    return key === ruleName || key.endsWith(`/${ruleName}`);
  });
  return match?.[1] || [];
}

function hasRule(results: LintResults, ruleName: string): boolean {
  return reportsFor(results, ruleName).length > 0;
}

const tsRules = (catalog as unknown as RuleCatalog).rules.filter(r => r.hasTsImplementation);

describe('lint rules', () => {
  it('plugin recommended config resolves every manifest rule', () => {
    const config = configs['plugin:bpmner/recommended'];
    assert.equal(Object.keys(config.rules).length, tsRules.length);
  });

  it('plugin all config upgrades every manifest rule to error', () => {
    const config = configs['plugin:bpmner/all'];
    assert.deepEqual(
      Object.values(config.rules),
      tsRules.map(() => 'error')
    );
  });

  it('every manifest rule has generated markdown docs', () => {
    for (const rule of tsRules) {
      assert.equal(typeof customRuleDocs[`${BPMNER_PLUGIN_PREFIX}/${rule.id}`], 'string');
      assert.ok(customRuleDocs[`${BPMNER_PLUGIN_PREFIX}/${rule.id}`].length > 0);
    }
  });

  it('getRuleConfig resolves every manifest rule from generated metadata', () => {
    for (const rule of (catalog as unknown as RuleCatalog).rules) {
      const config = getRuleConfig(rule.id);
      assert.equal(config.id, rule.id);
      assert.ok(config.errorMessages.default);
    }
  });

  it('migrated rule metadata maps Pkl severity to bpmnlint levels', () => {
    assert.equal(getBpmnlintLevel('bpmner/name-uncommon-abbreviations'), 'warn');
    assert.equal(getBpmnlintLevel('bpmnlint-plugin-bpmner/act-discouraged-business-verbs'), 'warn');
  });

  it('gtw-converging-gateway-unnamed records current auto-fix capability in generated metadata', () => {
    const config = getRuleConfig('gtw-converging-gateway-unnamed');
    assert.equal(config.autoFixable, true);
    assert.equal(config.fixStrategy, 'attribute-mutation');
  });

  it('valid baseline has no violations', async () => {
    const baseline = await lint(fixtures.validBaseline);
    for (const reports of Object.values(baseline)) {
      assert.equal(reports.length, 0);
    }
  });

  it('plugin all exposes rules as errors through resolved config', async () => {
    const results = await lintWithConfig(fixtures.validBaseline, customRuleConfigAll);
    for (const reports of Object.values(results)) {
      assert.equal(reports.length, 0);
    }
  });

  it('gen-bpmnsubset-allowed-elements', async () => {
    assert.ok(hasRule(await lint(fixtures.gen01Choreography), 'gen-bpmnsubset-allowed-elements'));
  });

  it('gen-no-duplicate-diagrams', async () => {
    assert.ok(hasRule(await lint(fixtures.gen02DuplicateDiagram), 'gen-no-duplicate-diagrams'));
  });

  it('act-loop-task-annotation — missing annotation', async () => {
    assert.ok(hasRule(await lint(fixtures.act12LoopWithoutAnnotation), 'act-loop-task-annotation'));
  });

  it('act-loop-task-annotation — equivalent annotation is clean', async () => {
    assert.ok(!hasRule(await lint(fixtures.act12LoopWithEquivalentAnnotation), 'act-loop-task-annotation'));
  });

  it('act-mi-task-annotation — missing annotation', async () => {
    assert.ok(hasRule(await lint(fixtures.act13MiWithoutAnnotation), 'act-mi-task-annotation'));
  });

  it('act-mi-task-annotation — equivalent annotation is clean', async () => {
    assert.ok(!hasRule(await lint(fixtures.act13MiWithEquivalentAnnotation), 'act-mi-task-annotation'));
  });

  it('evt-start-no-incoming', async () => {
    assert.ok(hasRule(await lint(fixtures.evt10StartWithIncoming), 'evt-start-no-incoming'));
  });

  it('evt-message-start-has-message-flow', async () => {
    assert.ok(hasRule(await lint(fixtures.evt11MessageStartWithoutMessageFlow), 'evt-message-start-has-message-flow'));
  });

  it('evt-boundary-event-constraints', async () => {
    assert.ok(hasRule(await lint(fixtures.evt14InvalidBoundary), 'evt-boundary-event-constraints'));
  });

  it('evt-error-end-boundary-pair', async () => {
    assert.ok(hasRule(await lint(fixtures.evt15UnmatchedErrorEnd), 'evt-error-end-boundary-pair'));
  });

  it('evt-link-event-pairing', async () => {
    assert.ok(hasRule(await lint(fixtures.evt16UnpairedLink), 'evt-link-event-pairing'));
  });

  it('gtw-event-based-direct-events', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw11EventBasedToTask), 'gtw-event-based-direct-events'));
  });

  it('gtw-diverging-flow-names', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw12UnnamedDivergingFlow), 'gtw-diverging-flow-names'));
  });

  it('flow-sequence-flow-within-pool', async () => {
    assert.ok(hasRule(await lint(fixtures.flow01CrossPoolSequence), 'flow-sequence-flow-within-pool'));
  });

  it('msg-message-flow-across-pools', async () => {
    assert.ok(hasRule(await lint(fixtures.msg01SamePoolMessage), 'msg-message-flow-across-pools'));
  });

  it('assoc-required-annotation-association', async () => {
    assert.ok(hasRule(await lint(fixtures.assoc01LoopWithoutAssociation), 'assoc-required-annotation-association'));
  });

  it('data-no-type-words-in-data-name', async () => {
    assert.ok(hasRule(await lint(fixtures.data01TypeWordsInDataName), 'data-no-type-words-in-data-name'));
  });

  it('name-no-element-type-words', async () => {
    assert.ok(hasRule(await lint(fixtures.name03TypeWordsInElementName), 'name-no-element-type-words'));
  });

  it('act-verb-object-name — invalid', async () => {
    const reports = reportsFor(await lint(fixtures.act01Invalid), 'act-verb-object-name');
    assert.equal(reports[0]?.message, getRuleMessage('act-verb-object-name', 'missingVerb'));
  });

  it('act-verb-object-name — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.act01Valid), 'act-verb-object-name'));
  });

  it('act-verb-object-name — phrasal verb', async () => {
    assert.ok(!hasRule(await lint(fixtures.act01PhrasalVerbValid), 'act-verb-object-name'));
  });

  it('act-verb-object-name — uppercase label', async () => {
    assert.ok(!hasRule(await lint(fixtures.act01UppercaseLabelValid), 'act-verb-object-name'));
  });

  it('act-verb-object-name — too short message comes from metadata', async () => {
    const xml = fixtures.act01Invalid.replace('Order creation', 'Approve');
    const reports = reportsFor(await lint(xml), 'act-verb-object-name');
    assert.equal(reports[0]?.message, getRuleMessage('act-verb-object-name', 'tooShort'));
  });

  it('act-activity-label-capitalization — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.act02Invalid), 'act-activity-label-capitalization'));
  });

  it('act-activity-label-capitalization — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.act02Valid), 'act-activity-label-capitalization'));
  });

  it('act-discouraged-business-verbs — invalid', async () => {
    const config = getStaticConfig<{ discouragedLeadingVerbs: string[] }>('act-discouraged-business-verbs');
    assert.ok(config.discouragedLeadingVerbs.includes('handle'));
    const reports = reportsFor(await lint(fixtures.act03Invalid), 'act-discouraged-business-verbs');
    assert.equal(reports[0]?.message, getRuleMessage('act-discouraged-business-verbs'));
  });

  it('act-discouraged-business-verbs — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.act03Valid), 'act-discouraged-business-verbs'));
  });

  it('gtw-diverging-gateway-question — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw01Invalid), 'gtw-diverging-gateway-question'));
  });

  it('gtw-diverging-gateway-question — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw01Valid), 'gtw-diverging-gateway-question'));
  });

  it('gtw-diverging-gateway-question — no question mark', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw01ValidNoQuestionMark), 'gtw-diverging-gateway-question'));
  });

  it('gtw-converging-gateway-unnamed — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw02Invalid), 'gtw-converging-gateway-unnamed'));
  });

  it('gtw-converging-gateway-unnamed — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw02Valid), 'gtw-converging-gateway-unnamed'));
  });

  it('gtw-gateway-no-work-label — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw03Invalid), 'gtw-gateway-no-work-label'));
  });

  it('gtw-gateway-no-work-label — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw03Valid), 'gtw-gateway-no-work-label'));
  });

  it('flow-diverging-flow-outcome-label — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.flow02Invalid), 'flow-diverging-flow-outcome-label'));
  });

  it('flow-diverging-flow-outcome-label — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.flow02Valid), 'flow-diverging-flow-outcome-label'));
  });

  it('evt-intermediate-event-not-action — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.evt13Invalid), 'evt-intermediate-event-not-action'));
  });

  it('evt-intermediate-event-not-action — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.evt13Valid), 'evt-intermediate-event-not-action'));
  });

  it('evt-event-state-name — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.evt01Invalid), 'evt-event-state-name'));
  });

  it('evt-event-state-name — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.evt01Valid), 'evt-event-state-name'));
  });

  it('evt-event-state-pattern — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.evt02Invalid), 'evt-event-state-pattern'));
  });

  it('evt-event-state-pattern — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.evt02Valid), 'evt-event-state-pattern'));
  });

  it('msg-message-flow-name-pattern — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.msg02Invalid), 'msg-message-flow-name-pattern'));
  });

  it('msg-message-flow-name-pattern — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.msg02Valid), 'msg-message-flow-name-pattern'));
  });

  it('msg-message-flow-name-pattern — uppercase verb invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.msg02UppercaseVerbInvalid), 'msg-message-flow-name-pattern'));
  });

  it('msg-message-flow-name-pattern — past participle noun valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.msg02PastParticipleNounValid), 'msg-message-flow-name-pattern'));
  });

  it('name-uncommon-abbreviations — invalid', async () => {
    const reports = reportsFor(await lint(fixtures.name02Invalid), 'name-uncommon-abbreviations');
    assert.equal(reports[0]?.message, getRuleMessage('name-uncommon-abbreviations'));
  });

  it('name-uncommon-abbreviations — valid', async () => {
    const config = getStaticConfig<{ commonAcronyms: string[] }>('name-uncommon-abbreviations');
    assert.ok(config.commonAcronyms.includes('BPMNER'));
    assert.ok(!hasRule(await lint(fixtures.name02Valid), 'name-uncommon-abbreviations'));
  });

  it('name-business-meaningful-label — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.name01Invalid), 'name-business-meaningful-label'));
  });

  it('name-business-meaningful-label — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.name01Valid), 'name-business-meaningful-label'));
  });
});
