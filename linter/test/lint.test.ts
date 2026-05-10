import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import BpmnModdle from 'bpmn-moddle';
import { Linter } from 'bpmnlint';
import { configs, customRuleDocs, resolver } from '../src/generated/static-rules';
import { getBpmnlintLevel, getRuleConfig, getRuleMessage, getStaticConfig } from '../src/rule-config';
import { customRuleManifest, KLM_PLUGIN_PREFIX } from '../src/rule-manifest';
import { fixtures } from './fixtures';

type Report = {
  id?: string;
  message: string;
  category?: string;
};

type LintResults = Record<string, Report[]>;

class PluginResolver {
  resolveRule = resolver.resolveRule;
  resolveConfig = resolver.resolveConfig;
}

const customRuleConfig = {
  extends: ['plugin:klm/recommended'],
};

const customRuleConfigAll = {
  extends: ['plugin:klm/all'],
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

describe('lint rules', () => {
  it('plugin recommended config resolves every manifest rule', () => {
    const config = configs['plugin:klm/recommended'];
    assert.equal(Object.keys(config.rules).length, customRuleManifest.length);
  });

  it('plugin all config upgrades every manifest rule to error', () => {
    const config = configs['plugin:klm/all'];
    assert.deepEqual(
      Object.values(config.rules),
      customRuleManifest.map(() => 'error')
    );
  });

  it('every manifest rule has generated markdown docs', () => {
    for (const { id } of customRuleManifest) {
      assert.equal(typeof customRuleDocs[`${KLM_PLUGIN_PREFIX}/${id}`], 'string');
      assert.ok(customRuleDocs[`${KLM_PLUGIN_PREFIX}/${id}`].length > 0);
    }
  });

  it('migrated rule metadata maps Pkl severity to bpmnlint levels', () => {
    assert.equal(getBpmnlintLevel('klm/name-02-uncommon-abbreviations'), 'warn');
    assert.equal(getBpmnlintLevel('bpmnlint-plugin-klm/act-03-discouraged-business-verbs'), 'warn');
  });

  it('unmigrated rules use manifest compatibility metadata', () => {
    const config = getRuleConfig('name-01-business-meaningful-label');
    assert.equal(config.severity, 'warning');
    assert.equal(config.autoFixable, false);
    assert.deepEqual(config.staticConfig, {});
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

  it('gen-01-klops-allowed-elements', async () => {
    assert.ok(hasRule(await lint(fixtures.gen01Choreography), 'gen-01-klops-allowed-elements'));
  });

  it('gen-02-no-duplicate-diagrams', async () => {
    assert.ok(hasRule(await lint(fixtures.gen02DuplicateDiagram), 'gen-02-no-duplicate-diagrams'));
  });

  it('act-12-loop-task-annotation — missing annotation', async () => {
    assert.ok(hasRule(await lint(fixtures.act12LoopWithoutAnnotation), 'act-12-loop-task-annotation'));
  });

  it('act-12-loop-task-annotation — equivalent annotation is clean', async () => {
    assert.ok(!hasRule(await lint(fixtures.act12LoopWithEquivalentAnnotation), 'act-12-loop-task-annotation'));
  });

  it('act-13-mi-task-annotation — missing annotation', async () => {
    assert.ok(hasRule(await lint(fixtures.act13MiWithoutAnnotation), 'act-13-mi-task-annotation'));
  });

  it('act-13-mi-task-annotation — equivalent annotation is clean', async () => {
    assert.ok(!hasRule(await lint(fixtures.act13MiWithEquivalentAnnotation), 'act-13-mi-task-annotation'));
  });

  it('evt-10-start-no-incoming', async () => {
    assert.ok(hasRule(await lint(fixtures.evt10StartWithIncoming), 'evt-10-start-no-incoming'));
  });

  it('evt-11-message-start-has-message-flow', async () => {
    assert.ok(hasRule(await lint(fixtures.evt11MessageStartWithoutMessageFlow), 'evt-11-message-start-has-message-flow'));
  });

  it('evt-14-boundary-event-constraints', async () => {
    assert.ok(hasRule(await lint(fixtures.evt14InvalidBoundary), 'evt-14-boundary-event-constraints'));
  });

  it('evt-15-error-end-boundary-pair', async () => {
    assert.ok(hasRule(await lint(fixtures.evt15UnmatchedErrorEnd), 'evt-15-error-end-boundary-pair'));
  });

  it('evt-16-link-event-pairing', async () => {
    assert.ok(hasRule(await lint(fixtures.evt16UnpairedLink), 'evt-16-link-event-pairing'));
  });

  it('gtw-11-event-based-direct-events', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw11EventBasedToTask), 'gtw-11-event-based-direct-events'));
  });

  it('gtw-12-diverging-flow-names', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw12UnnamedDivergingFlow), 'gtw-12-diverging-flow-names'));
  });

  it('flow-01-sequence-flow-within-pool', async () => {
    assert.ok(hasRule(await lint(fixtures.flow01CrossPoolSequence), 'flow-01-sequence-flow-within-pool'));
  });

  it('msg-01-message-flow-across-pools', async () => {
    assert.ok(hasRule(await lint(fixtures.msg01SamePoolMessage), 'msg-01-message-flow-across-pools'));
  });

  it('assoc-01-required-annotation-association', async () => {
    assert.ok(hasRule(await lint(fixtures.assoc01LoopWithoutAssociation), 'assoc-01-required-annotation-association'));
  });

  it('data-01-no-type-words-in-data-name', async () => {
    assert.ok(hasRule(await lint(fixtures.data01TypeWordsInDataName), 'data-01-no-type-words-in-data-name'));
  });

  it('name-03-no-element-type-words', async () => {
    assert.ok(hasRule(await lint(fixtures.name03TypeWordsInElementName), 'name-03-no-element-type-words'));
  });

  it('act-01-verb-object-name — invalid', async () => {
    const reports = reportsFor(await lint(fixtures.act01Invalid), 'act-01-verb-object-name');
    assert.equal(reports[0]?.message, getRuleMessage('act-01-verb-object-name', 'missingVerb'));
  });

  it('act-01-verb-object-name — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.act01Valid), 'act-01-verb-object-name'));
  });

  it('act-01-verb-object-name — phrasal verb', async () => {
    assert.ok(!hasRule(await lint(fixtures.act01PhrasalVerbValid), 'act-01-verb-object-name'));
  });

  it('act-01-verb-object-name — uppercase label', async () => {
    assert.ok(!hasRule(await lint(fixtures.act01UppercaseLabelValid), 'act-01-verb-object-name'));
  });

  it('act-01-verb-object-name — too short message comes from metadata', async () => {
    const xml = fixtures.act01Invalid.replace('Order creation', 'Approve');
    const reports = reportsFor(await lint(xml), 'act-01-verb-object-name');
    assert.equal(reports[0]?.message, getRuleMessage('act-01-verb-object-name', 'tooShort'));
  });

  it('act-02-activity-label-capitalization — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.act02Invalid), 'act-02-activity-label-capitalization'));
  });

  it('act-02-activity-label-capitalization — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.act02Valid), 'act-02-activity-label-capitalization'));
  });

  it('act-03-discouraged-business-verbs — invalid', async () => {
    const config = getStaticConfig<{ discouragedLeadingVerbs: string[] }>('act-03-discouraged-business-verbs');
    assert.ok(config.discouragedLeadingVerbs.includes('handle'));
    const reports = reportsFor(await lint(fixtures.act03Invalid), 'act-03-discouraged-business-verbs');
    assert.equal(reports[0]?.message, getRuleMessage('act-03-discouraged-business-verbs'));
  });

  it('act-03-discouraged-business-verbs — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.act03Valid), 'act-03-discouraged-business-verbs'));
  });

  it('gtw-01-diverging-gateway-question — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw01Invalid), 'gtw-01-diverging-gateway-question'));
  });

  it('gtw-01-diverging-gateway-question — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw01Valid), 'gtw-01-diverging-gateway-question'));
  });

  it('gtw-01-diverging-gateway-question — no question mark', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw01ValidNoQuestionMark), 'gtw-01-diverging-gateway-question'));
  });

  it('gtw-02-converging-gateway-unnamed — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw02Invalid), 'gtw-02-converging-gateway-unnamed'));
  });

  it('gtw-02-converging-gateway-unnamed — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw02Valid), 'gtw-02-converging-gateway-unnamed'));
  });

  it('gtw-03-gateway-no-work-label — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.gtw03Invalid), 'gtw-03-gateway-no-work-label'));
  });

  it('gtw-03-gateway-no-work-label — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.gtw03Valid), 'gtw-03-gateway-no-work-label'));
  });

  it('flow-02-diverging-flow-outcome-label — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.flow02Invalid), 'flow-02-diverging-flow-outcome-label'));
  });

  it('flow-02-diverging-flow-outcome-label — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.flow02Valid), 'flow-02-diverging-flow-outcome-label'));
  });

  it('evt-13-intermediate-event-not-action — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.evt13Invalid), 'evt-13-intermediate-event-not-action'));
  });

  it('evt-13-intermediate-event-not-action — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.evt13Valid), 'evt-13-intermediate-event-not-action'));
  });

  it('evt-01-event-state-name — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.evt01Invalid), 'evt-01-event-state-name'));
  });

  it('evt-01-event-state-name — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.evt01Valid), 'evt-01-event-state-name'));
  });

  it('evt-02-event-state-pattern — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.evt02Invalid), 'evt-02-event-state-pattern'));
  });

  it('evt-02-event-state-pattern — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.evt02Valid), 'evt-02-event-state-pattern'));
  });

  it('msg-02-message-flow-name-pattern — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.msg02Invalid), 'msg-02-message-flow-name-pattern'));
  });

  it('msg-02-message-flow-name-pattern — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.msg02Valid), 'msg-02-message-flow-name-pattern'));
  });

  it('msg-02-message-flow-name-pattern — uppercase verb invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.msg02UppercaseVerbInvalid), 'msg-02-message-flow-name-pattern'));
  });

  it('msg-02-message-flow-name-pattern — past participle noun valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.msg02PastParticipleNounValid), 'msg-02-message-flow-name-pattern'));
  });

  it('name-02-uncommon-abbreviations — invalid', async () => {
    const reports = reportsFor(await lint(fixtures.name02Invalid), 'name-02-uncommon-abbreviations');
    assert.equal(reports[0]?.message, getRuleMessage('name-02-uncommon-abbreviations'));
  });

  it('name-02-uncommon-abbreviations — valid', async () => {
    const config = getStaticConfig<{ commonAcronyms: string[] }>('name-02-uncommon-abbreviations');
    assert.ok(config.commonAcronyms.includes('KLM'));
    assert.ok(!hasRule(await lint(fixtures.name02Valid), 'name-02-uncommon-abbreviations'));
  });

  it('name-01-business-meaningful-label — invalid', async () => {
    assert.ok(hasRule(await lint(fixtures.name01Invalid), 'name-01-business-meaningful-label'));
  });

  it('name-01-business-meaningful-label — valid', async () => {
    assert.ok(!hasRule(await lint(fixtures.name01Valid), 'name-01-business-meaningful-label'));
  });
});
