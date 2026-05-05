import test from 'node:test';
import assert from 'node:assert/strict';
import { hasRule, lint } from './test-helpers';
import { phase1Fixtures } from './fixtures/phase1';

test('valid baseline does not trigger BPMNER phase 1 rules', async () => {
  const results = await lint(phase1Fixtures.validBaseline);

  for (const reports of Object.values(results)) {
    assert.equal(reports.length, 0);
  }
});

test('GEN-01 flags choreography elements', async () => {
  const results = await lint(phase1Fixtures.gen01Choreography);
  assert.equal(hasRule(results, 'gen-01-bpmnsubset-allowed-elements'), true);
});

test('ACT-12 flags loop task without Loop until annotation', async () => {
  const results = await lint(phase1Fixtures.act12LoopWithoutAnnotation);
  assert.equal(hasRule(results, 'act-12-loop-task-annotation'), true);
});

test('ACT-12 accepts loop annotation with equivalent phrasing', async () => {
  const results = await lint(phase1Fixtures.act12LoopWithEquivalentAnnotation);
  assert.equal(hasRule(results, 'act-12-loop-task-annotation'), false);
});

test('ACT-13 flags multi-instance task without For each annotation', async () => {
  const results = await lint(phase1Fixtures.act13MiWithoutAnnotation);
  assert.equal(hasRule(results, 'act-13-mi-task-annotation'), true);
});

test('ACT-13 accepts multi-instance annotation with equivalent phrasing', async () => {
  const results = await lint(phase1Fixtures.act13MiWithEquivalentAnnotation);
  assert.equal(hasRule(results, 'act-13-mi-task-annotation'), false);
});

test('EVT-10 flags start event with incoming flow', async () => {
  const results = await lint(phase1Fixtures.evt10StartWithIncoming);
  assert.equal(hasRule(results, 'evt-10-start-no-incoming'), true);
});

test('EVT-11 flags message start event without incoming message flow', async () => {
  const results = await lint(phase1Fixtures.evt11MessageStartWithoutMessageFlow);
  assert.equal(hasRule(results, 'evt-11-message-start-has-message-flow'), true);
});

test('EVT-14 flags invalid boundary event structure', async () => {
  const results = await lint(phase1Fixtures.evt14InvalidBoundary);
  assert.equal(hasRule(results, 'evt-14-boundary-event-constraints'), true);
});

test('EVT-15 flags unmatched error end event', async () => {
  const results = await lint(phase1Fixtures.evt15UnmatchedErrorEnd);
  assert.equal(hasRule(results, 'evt-15-error-end-boundary-pair'), true);
});

test('EVT-16 flags unpaired link event', async () => {
  const results = await lint(phase1Fixtures.evt16UnpairedLink);
  assert.equal(hasRule(results, 'evt-16-link-event-pairing'), true);
});

test('GTW-11 flags event-based gateway not leading directly to event or receive task', async () => {
  const results = await lint(phase1Fixtures.gtw11EventBasedToTask);
  assert.equal(hasRule(results, 'gtw-11-event-based-direct-events'), true);
});

test('GTW-12 flags unnamed diverging sequence flow', async () => {
  const results = await lint(phase1Fixtures.gtw12UnnamedDivergingFlow);
  assert.equal(hasRule(results, 'gtw-12-diverging-flow-names'), true);
});

test('FLOW-01 flags sequence flow across pools', async () => {
  const results = await lint(phase1Fixtures.flow01CrossPoolSequence);
  assert.equal(hasRule(results, 'flow-01-sequence-flow-within-pool'), true);
});

test('MSG-01 flags message flow inside same pool', async () => {
  const results = await lint(phase1Fixtures.msg01SamePoolMessage);
  assert.equal(hasRule(results, 'msg-01-message-flow-across-pools'), true);
});

test('ASSOC-01 flags loop activity without any associated annotation', async () => {
  const results = await lint(phase1Fixtures.assoc01LoopWithoutAssociation);
  assert.equal(hasRule(results, 'assoc-01-required-annotation-association'), true);
});

test('DATA-01 flags data names with BPMN element type words', async () => {
  const results = await lint(phase1Fixtures.data01TypeWordsInDataName);
  assert.equal(hasRule(results, 'data-01-no-type-words-in-data-name'), true);
});

test('NAME-03 flags element names with BPMN element type words', async () => {
  const results = await lint(phase1Fixtures.name03TypeWordsInElementName);
  assert.equal(hasRule(results, 'name-03-no-element-type-words'), true);
});
