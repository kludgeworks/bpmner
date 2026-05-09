import { describe, it, before } from 'node:test';
import assert from 'node:assert/strict';
import { fixtures } from './fixtures';

type BpmnLinterApi = {
  lintXml(xml: string, config?: unknown): Promise<string>;
  getRules(config?: unknown): Record<string, string>;
  getInvalidRules(config?: unknown): string[];
  getRuleDocs(ruleNames: string[]): Record<string, string>;
};

describe('BpmnLinterApi bundle smoke test', () => {
  let api: BpmnLinterApi;

  before(() => {
    require('../src/linter-bundle');
    const globalApi = (globalThis as typeof globalThis & { BpmnLinterApi?: BpmnLinterApi }).BpmnLinterApi;
    assert.ok(globalApi, 'BpmnLinterApi should be defined on globalThis');
    api = globalApi!;
  });

  it('reports start-event-required on a minimal process', async () => {
    const issues: Array<{ rule: string }> = JSON.parse(
      await api.lintXml(`<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
  <bpmn:process id="Process_1" />
</bpmn:definitions>`)
    );
    assert.ok(issues.some((i) => i.rule === 'start-event-required'));
  });

  it('does not enable BPMNER rules unless explicitly configured', async () => {
    const issues: Array<{ rule: string }> = JSON.parse(await api.lintXml(fixtures.gen02DuplicateDiagram));
    assert.ok(!issues.some((i) => i.rule === 'bpmner/gen-02-no-duplicate-diagrams'));
  });

  it('reports bpmner/gen-02-no-duplicate-diagrams when plugin recommended is enabled', async () => {
    const issues: Array<{ rule: string }> = JSON.parse(
      await api.lintXml(fixtures.gen02DuplicateDiagram, {
        extends: ['bpmnlint:recommended', 'plugin:bpmner/recommended'],
      })
    );
    assert.ok(issues.some((i) => i.rule === 'bpmner/gen-02-no-duplicate-diagrams'));
  });

  it('resolves active rules from extends + rules config', () => {
    const rules = api.getRules({
      extends: ['bpmnlint:recommended', 'plugin:bpmner/recommended'],
      rules: {
        'bpmner/gen-02-no-duplicate-diagrams': 'warn',
      },
    });
    assert.equal(rules['bpmner/gen-02-no-duplicate-diagrams'], 'warn');
    assert.equal(rules['start-event-required'], 'error');
  });

  it('returns no invalid rules for valid config', () => {
    const invalidRules = api.getInvalidRules({
      extends: ['bpmnlint:recommended', 'plugin:bpmner/recommended'],
      rules: {
        'bpmner/gen-02-no-duplicate-diagrams': 'warn',
      },
    });

    assert.deepEqual(invalidRules, []);
  });

  it('returns invalid rule ids from resolved config', () => {
    const invalidRules = api.getInvalidRules({
      extends: ['bpmnlint:recommended'],
      rules: {
        'bpmneract-01-verb-object-name': 'error',
      },
    });

    assert.deepEqual(invalidRules, ['bpmneract-01-verb-object-name']);
  });

  it('returns markdown docs for BPMNER rules', () => {
    const docs = api.getRuleDocs(['bpmner/gen-02-no-duplicate-diagrams']);
    assert.ok(docs['bpmner/gen-02-no-duplicate-diagrams'].includes('# gen-02-no-duplicate-diagrams'));
  });
});
