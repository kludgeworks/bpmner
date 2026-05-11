import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { fixtures } from './fixtures';
import { BpmnLinterApi } from '../src/linter-bundle';

const api = BpmnLinterApi;

describe('BpmnLinterApi bundle smoke test', () => {
  it('reports start-event-required on a minimal process', async () => {
    const issues: Array<{ rule: string }> = JSON.parse(
      await api.lintXml(fixtures.validBaseline, {
        extends: ['bpmnlint:recommended'],
      })
    );

    assert.ok(issues.some((i) => i.rule === 'start-event-required'));
  });

  it('does not enable BPMNER rules unless explicitly configured', async () => {
    const issues: Array<{ rule: string }> = JSON.parse(
      await api.lintXml(fixtures.gen02DuplicateDiagram, {
        extends: ['bpmnlint:recommended'],
      })
    );

    assert.ok(!issues.some((i) => i.rule.includes('bpmner/')));
  });

  it('reports bpmner/gen-no-duplicate-diagrams when plugin recommended is enabled', async () => {
    const issues: Array<{ rule: string }> = JSON.parse(
      await api.lintXml(fixtures.gen02DuplicateDiagram, {
        extends: ['plugin:bpmner/recommended'],
      })
    );

    assert.ok(issues.some((i) => i.rule === 'bpmner/gen-no-duplicate-diagrams'));
  });

  it('resolves active rules from extends + rules config', () => {
    const rules = api.getRules({
      extends: ['plugin:bpmner/recommended'],
      rules: {
        'bpmner/act-verb-object-name': 'error',
      },
    });

    assert.equal(rules['bpmner/act-verb-object-name'], 'error');
  });

  it('returns no invalid rules for valid config', () => {
    const invalidRules = api.getInvalidRules({
      extends: ['plugin:bpmner/recommended'],
      rules: {
        'bpmner/act-verb-object-name': 'error',
      },
    });

    assert.deepEqual(invalidRules, []);
  });

  it('returns invalid rule ids from resolved config', () => {
    const invalidRules = api.getInvalidRules({
      rules: {
        'bpmner/unknown-rule': 'error',
      },
    });

    assert.deepEqual(invalidRules, ['bpmner/unknown-rule']);
  });

  it('returns markdown docs for BPMNER rules', () => {
    const docs = api.getRuleDocs(['bpmner/gen-no-duplicate-diagrams']);
    const doc = docs['bpmner/gen-no-duplicate-diagrams'];
    assert.ok(doc && doc.includes('# gen-no-duplicate-diagrams'));
  });

  it('fixXml clears a named converging gateway', async () => {
    const issues = JSON.parse(
      await api.lintXml(fixtures.gtw02Invalid, {
        extends: ['plugin:bpmner/recommended'],
      })
    );

    const result = JSON.parse(await api.fixXml(fixtures.gtw02Invalid, JSON.stringify(issues), JSON.stringify({
        extends: ['plugin:bpmner/recommended'],
    })));

    assert.equal(result.changed, true);
    assert.equal(result.applied.length, 1);
    assert.equal(result.applied[0].rule, 'bpmner/gtw-converging-gateway-unnamed');
    assert.equal(result.applied[0].elementId, 'Gateway_1');
    assert.ok(!result.xml.includes('name="Decision merged"'));

    const postFixIssues: Array<{ rule: string }> = JSON.parse(
      await api.lintXml(result.xml, {
        extends: ['plugin:bpmner/recommended'],
      })
    );
    assert.ok(!postFixIssues.some((i) => i.rule === 'bpmner/gtw-converging-gateway-unnamed'));
  });

  it('fixXml returns a skip entry for non-fixable rules', async () => {
    const issues = [
      {
        id: 'Task_1',
        rule: 'bpmner/act-verb-object-name',
        message: 'invalid name',
      },
    ];

    const result = JSON.parse(await api.fixXml(fixtures.validBaseline, JSON.stringify(issues)));

    assert.equal(result.changed, false);
    assert.equal(result.applied.length, 0);
    assert.equal(result.skipped.length, 1);
    assert.equal(result.skipped[0].rule, 'bpmner/act-verb-object-name');
  });

  it('fixXml returns a structured parse error for invalid XML', async () => {
    const result = JSON.parse(await api.fixXml('invalid-xml', '[]'));

    assert.equal(result.changed, false);
    assert.equal(result.errors.length, 1);
    assert.ok(result.errors[0].message.includes('failed to parse') || result.errors[0].message.includes('required args'));
  });
});
