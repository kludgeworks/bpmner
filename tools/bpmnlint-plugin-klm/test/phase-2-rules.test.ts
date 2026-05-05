import test from 'node:test';
import assert from 'node:assert/strict';
import { hasRule, lint } from './test-helpers';
import { phase2Fixtures } from './fixtures/phase2';

test('ACT-01 warns on non-verb-object activity names', async () => {
  const invalid = await lint(phase2Fixtures.act01Invalid);
  const valid = await lint(phase2Fixtures.act01Valid);

  assert.equal(hasRule(invalid, 'act-01-verb-object-name'), true);
  assert.equal(hasRule(valid, 'act-01-verb-object-name'), false);
});

test('ACT-01 accepts phrasal verb activity names', async () => {
  const results = await lint(phase2Fixtures.act01PhrasalVerbValid);
  assert.equal(hasRule(results, 'act-01-verb-object-name'), false);
});

test('ACT-01 accepts uppercase leading verb labels', async () => {
  const results = await lint(phase2Fixtures.act01UppercaseLabelValid);
  assert.equal(hasRule(results, 'act-01-verb-object-name'), false);
});

test('ACT-02 warns on non-sentence-case activity labels', async () => {
  const invalid = await lint(phase2Fixtures.act02Invalid);
  const valid = await lint(phase2Fixtures.act02Valid);

  assert.equal(hasRule(invalid, 'act-02-activity-label-capitalization'), true);
  assert.equal(hasRule(valid, 'act-02-activity-label-capitalization'), false);
});

test('ACT-03 warns on discouraged generic business verbs', async () => {
  const invalid = await lint(phase2Fixtures.act03Invalid);
  const valid = await lint(phase2Fixtures.act03Valid);

  assert.equal(hasRule(invalid, 'act-03-discouraged-business-verbs'), true);
  assert.equal(hasRule(valid, 'act-03-discouraged-business-verbs'), false);
});

test('GTW-01 warns when diverging gateway label is not a question', async () => {
  const invalid = await lint(phase2Fixtures.gtw01Invalid);
  const valid = await lint(phase2Fixtures.gtw01Valid);
  const validNoQuestionMark = await lint(phase2Fixtures.gtw01ValidNoQuestionMark);

  assert.equal(hasRule(invalid, 'gtw-01-diverging-gateway-question'), true);
  assert.equal(hasRule(valid, 'gtw-01-diverging-gateway-question'), false);
  assert.equal(hasRule(validNoQuestionMark, 'gtw-01-diverging-gateway-question'), false);
});

test('GTW-02 warns when converging gateway has a label', async () => {
  const invalid = await lint(phase2Fixtures.gtw02Invalid);
  const valid = await lint(phase2Fixtures.gtw02Valid);

  assert.equal(hasRule(invalid, 'gtw-02-converging-gateway-unnamed'), true);
  assert.equal(hasRule(valid, 'gtw-02-converging-gateway-unnamed'), false);
});

test('GTW-03 warns when gateway label describes work', async () => {
  const invalid = await lint(phase2Fixtures.gtw03Invalid);
  const valid = await lint(phase2Fixtures.gtw03Valid);

  assert.equal(hasRule(invalid, 'gtw-03-gateway-no-work-label'), true);
  assert.equal(hasRule(valid, 'gtw-03-gateway-no-work-label'), false);
});

test('FLOW-02 warns when diverging gateway outcome flow is unnamed', async () => {
  const invalid = await lint(phase2Fixtures.flow02Invalid);
  const valid = await lint(phase2Fixtures.flow02Valid);

  assert.equal(hasRule(invalid, 'flow-02-diverging-flow-outcome-label'), true);
  assert.equal(hasRule(valid, 'flow-02-diverging-flow-outcome-label'), false);
});

test('EVT-13 warns when intermediate event label is action-style', async () => {
  const invalid = await lint(phase2Fixtures.evt13Invalid);
  const valid = await lint(phase2Fixtures.evt13Valid);

  assert.equal(hasRule(invalid, 'evt-13-intermediate-event-not-action'), true);
  assert.equal(hasRule(valid, 'evt-13-intermediate-event-not-action'), false);
});

test('EVT-01 warns when event label is action-style', async () => {
  const invalid = await lint(phase2Fixtures.evt01Invalid);
  const valid = await lint(phase2Fixtures.evt01Valid);

  assert.equal(hasRule(invalid, 'evt-01-event-state-name'), true);
  assert.equal(hasRule(valid, 'evt-01-event-state-name'), false);
});

test('EVT-02 warns when event label does not match state/result pattern', async () => {
  const invalid = await lint(phase2Fixtures.evt02Invalid);
  const valid = await lint(phase2Fixtures.evt02Valid);

  assert.equal(hasRule(invalid, 'evt-02-event-state-pattern'), true);
  assert.equal(hasRule(valid, 'evt-02-event-state-pattern'), false);
});

test('MSG-02 warns on action-style message flow names', async () => {
  const invalid = await lint(phase2Fixtures.msg02Invalid);
  const valid = await lint(phase2Fixtures.msg02Valid);

  assert.equal(hasRule(invalid, 'msg-02-message-flow-name-pattern'), true);
  assert.equal(hasRule(valid, 'msg-02-message-flow-name-pattern'), false);
});

test('MSG-02 warns on uppercase action-style message flow names', async () => {
  const results = await lint(phase2Fixtures.msg02UppercaseVerbInvalid);
  assert.equal(hasRule(results, 'msg-02-message-flow-name-pattern'), true);
});

test('MSG-02 accepts noun-led names with past participle terms', async () => {
  const results = await lint(phase2Fixtures.msg02PastParticipleNounValid);
  assert.equal(hasRule(results, 'msg-02-message-flow-name-pattern'), false);
});

test('NAME-02 warns on uncommon abbreviations', async () => {
  const invalid = await lint(phase2Fixtures.name02Invalid);
  const valid = await lint(phase2Fixtures.name02Valid);

  assert.equal(hasRule(invalid, 'name-02-uncommon-abbreviations'), true);
  assert.equal(hasRule(valid, 'name-02-uncommon-abbreviations'), false);
});

test('NAME-01 warns on technical/cryptic labels', async () => {
  const invalid = await lint(phase2Fixtures.name01Invalid);
  const valid = await lint(phase2Fixtures.name01Valid);

  assert.equal(hasRule(invalid, 'name-01-business-meaningful-label'), true);
  assert.equal(hasRule(valid, 'name-01-business-meaningful-label'), false);
});
