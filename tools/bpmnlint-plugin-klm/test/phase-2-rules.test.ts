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

test('GTW-01 warns when diverging gateway label is not a question', async () => {
  const invalid = await lint(phase2Fixtures.gtw01Invalid);
  const valid = await lint(phase2Fixtures.gtw01Valid);

  assert.equal(hasRule(invalid, 'gtw-01-diverging-gateway-question'), true);
  assert.equal(hasRule(valid, 'gtw-01-diverging-gateway-question'), false);
});

test('MSG-02 warns on action-style message flow names', async () => {
  const invalid = await lint(phase2Fixtures.msg02Invalid);
  const valid = await lint(phase2Fixtures.msg02Valid);

  assert.equal(hasRule(invalid, 'msg-02-message-flow-name-pattern'), true);
  assert.equal(hasRule(valid, 'msg-02-message-flow-name-pattern'), false);
});
