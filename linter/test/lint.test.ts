const assert = require('node:assert/strict');
import { phase1Fixtures } from './fixtures/phase1';
import { phase2Fixtures } from './fixtures/phase2';
import { hasRule, lint } from './test-helpers';

async function expectRule(xml: string, ruleName: string, expected: boolean) {
  const results = await lint(xml);
  assert.equal(hasRule(results, ruleName), expected, `Expected ${ruleName} to be ${expected ? 'reported' : 'clean'}`);
}

async function main() {
  const baseline = await lint(phase1Fixtures.validBaseline);

  for (const reports of Object.values(baseline)) {
    assert.equal(reports.length, 0);
  }

  await expectRule(phase1Fixtures.gen01Choreography, 'gen-01-bpmnsubset-allowed-elements', true);
  await expectRule(phase1Fixtures.gen02DuplicateDiagram, 'gen-02-no-duplicate-diagrams', true);
  await expectRule(phase1Fixtures.act12LoopWithoutAnnotation, 'act-12-loop-task-annotation', true);
  await expectRule(phase1Fixtures.act12LoopWithEquivalentAnnotation, 'act-12-loop-task-annotation', false);
  await expectRule(phase1Fixtures.act13MiWithoutAnnotation, 'act-13-mi-task-annotation', true);
  await expectRule(phase1Fixtures.act13MiWithEquivalentAnnotation, 'act-13-mi-task-annotation', false);
  await expectRule(phase1Fixtures.evt10StartWithIncoming, 'evt-10-start-no-incoming', true);
  await expectRule(phase1Fixtures.evt11MessageStartWithoutMessageFlow, 'evt-11-message-start-has-message-flow', true);
  await expectRule(phase1Fixtures.evt14InvalidBoundary, 'evt-14-boundary-event-constraints', true);
  await expectRule(phase1Fixtures.evt15UnmatchedErrorEnd, 'evt-15-error-end-boundary-pair', true);
  await expectRule(phase1Fixtures.evt16UnpairedLink, 'evt-16-link-event-pairing', true);
  await expectRule(phase1Fixtures.gtw11EventBasedToTask, 'gtw-11-event-based-direct-events', true);
  await expectRule(phase1Fixtures.gtw12UnnamedDivergingFlow, 'gtw-12-diverging-flow-names', true);
  await expectRule(phase1Fixtures.flow01CrossPoolSequence, 'flow-01-sequence-flow-within-pool', true);
  await expectRule(phase1Fixtures.msg01SamePoolMessage, 'msg-01-message-flow-across-pools', true);
  await expectRule(phase1Fixtures.assoc01LoopWithoutAssociation, 'assoc-01-required-annotation-association', true);
  await expectRule(phase1Fixtures.data01TypeWordsInDataName, 'data-01-no-type-words-in-data-name', true);
  await expectRule(phase1Fixtures.name03TypeWordsInElementName, 'name-03-no-element-type-words', true);

  await expectRule(phase2Fixtures.act01Invalid, 'act-01-verb-object-name', true);
  await expectRule(phase2Fixtures.act01Valid, 'act-01-verb-object-name', false);
  await expectRule(phase2Fixtures.act01PhrasalVerbValid, 'act-01-verb-object-name', false);
  await expectRule(phase2Fixtures.act01UppercaseLabelValid, 'act-01-verb-object-name', false);
  await expectRule(phase2Fixtures.act02Invalid, 'act-02-activity-label-capitalization', true);
  await expectRule(phase2Fixtures.act02Valid, 'act-02-activity-label-capitalization', false);
  await expectRule(phase2Fixtures.act03Invalid, 'act-03-discouraged-business-verbs', true);
  await expectRule(phase2Fixtures.act03Valid, 'act-03-discouraged-business-verbs', false);
  await expectRule(phase2Fixtures.gtw01Invalid, 'gtw-01-diverging-gateway-question', true);
  await expectRule(phase2Fixtures.gtw01Valid, 'gtw-01-diverging-gateway-question', false);
  await expectRule(phase2Fixtures.gtw01ValidNoQuestionMark, 'gtw-01-diverging-gateway-question', false);
  await expectRule(phase2Fixtures.gtw02Invalid, 'gtw-02-converging-gateway-unnamed', true);
  await expectRule(phase2Fixtures.gtw02Valid, 'gtw-02-converging-gateway-unnamed', false);
  await expectRule(phase2Fixtures.gtw03Invalid, 'gtw-03-gateway-no-work-label', true);
  await expectRule(phase2Fixtures.gtw03Valid, 'gtw-03-gateway-no-work-label', false);
  await expectRule(phase2Fixtures.flow02Invalid, 'flow-02-diverging-flow-outcome-label', true);
  await expectRule(phase2Fixtures.flow02Valid, 'flow-02-diverging-flow-outcome-label', false);
  await expectRule(phase2Fixtures.evt13Invalid, 'evt-13-intermediate-event-not-action', true);
  await expectRule(phase2Fixtures.evt13Valid, 'evt-13-intermediate-event-not-action', false);
  await expectRule(phase2Fixtures.evt01Invalid, 'evt-01-event-state-name', true);
  await expectRule(phase2Fixtures.evt01Valid, 'evt-01-event-state-name', false);
  await expectRule(phase2Fixtures.evt02Invalid, 'evt-02-event-state-pattern', true);
  await expectRule(phase2Fixtures.evt02Valid, 'evt-02-event-state-pattern', false);
  await expectRule(phase2Fixtures.msg02Invalid, 'msg-02-message-flow-name-pattern', true);
  await expectRule(phase2Fixtures.msg02Valid, 'msg-02-message-flow-name-pattern', false);
  await expectRule(phase2Fixtures.msg02UppercaseVerbInvalid, 'msg-02-message-flow-name-pattern', true);
  await expectRule(phase2Fixtures.msg02PastParticipleNounValid, 'msg-02-message-flow-name-pattern', false);
  await expectRule(phase2Fixtures.name02Invalid, 'name-02-uncommon-abbreviations', true);
  await expectRule(phase2Fixtures.name02Valid, 'name-02-uncommon-abbreviations', false);
  await expectRule(phase2Fixtures.name01Invalid, 'name-01-business-meaningful-label', true);
  await expectRule(phase2Fixtures.name01Valid, 'name-01-business-meaningful-label', false);
}

void main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
