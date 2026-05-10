import { describe, it, before } from 'node:test';
import assert from 'node:assert/strict';
import BpmnModdle from 'bpmn-moddle';
import { fixtures } from './fixtures';

type BpmnLinterApi = {
  lintXml(xml: string, config?: unknown): Promise<string>;
  fixXml(xml: string, issues?: unknown, config?: unknown, options?: unknown): Promise<string>;
  getRules(config?: unknown): Record<string, string>;
  getInvalidRules(config?: unknown): string[];
  getRuleDocs(ruleNames: string[]): Record<string, string>;
};

type AutoFixResult = {
  changed: boolean;
  xml: string;
  applied: Array<{ rule: string; elementId?: string; message: string }>;
  skipped: Array<{ rule: string; elementId?: string; message: string }>;
  errors: Array<{ rule: string; elementId?: string; message: string }>;
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

  it('fixXml clears a named converging gateway', async () => {
    const issues = JSON.parse(
      await api.lintXml(fixtures.gtw02Invalid, {
        extends: ['plugin:bpmner/recommended'],
      })
    );

    const result: AutoFixResult = JSON.parse(await api.fixXml(fixtures.gtw02Invalid, issues));

    assert.equal(result.changed, true);
    assert.equal(result.applied.length, 1);
    assert.equal(result.applied[0].rule, 'bpmner/gtw-02-converging-gateway-unnamed');
    assert.equal(result.applied[0].elementId, 'Gateway_1');
    assert.ok(!result.xml.includes('name="Decision merged"'));

    const postFixIssues: Array<{ rule: string }> = JSON.parse(
      await api.lintXml(result.xml, {
        extends: ['plugin:bpmner/recommended'],
      })
    );
    assert.ok(!postFixIssues.some((i) => i.rule === 'bpmner/gtw-02-converging-gateway-unnamed'));

    const moddle = new BpmnModdle();
    await moddle.fromXML(result.xml);
  });

  it('fixXml returns a skip entry for non-fixable rules', async () => {
    const result: AutoFixResult = JSON.parse(
      await api.fixXml(fixtures.validBaseline, [
        {
          id: 'Task_1',
          rule: 'start-event-required',
          message: 'Not fixable here',
        },
      ])
    );

    assert.equal(result.changed, false);
    assert.equal(result.xml, fixtures.validBaseline);
    assert.deepEqual(result.applied, []);
    assert.equal(result.skipped.length, 1);
    assert.equal(result.skipped[0].rule, 'start-event-required');
    assert.deepEqual(result.errors, []);
  });

  it('fixXml returns a structured parse error for invalid XML', async () => {
    const result: AutoFixResult = JSON.parse(
      await api.fixXml('<bpmn:definitions>', [
        {
          id: 'Gateway_1',
          rule: 'bpmner/gtw-02-converging-gateway-unnamed',
          message: 'Converging gateway should remain unnamed',
        },
      ])
    );

    assert.equal(result.changed, false);
    assert.equal(result.xml, '<bpmn:definitions>');
    assert.equal(result.applied.length, 0);
    assert.ok(result.errors.some((error) => error.rule === 'parse-error'));
  });
});
