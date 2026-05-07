import { describe, it, before } from 'node:test';
import assert from 'node:assert/strict';
import { fixtures } from './fixtures';

type BpmnLinterApi = {
  lintXml(xml: string, ruleOverrides?: Record<string, string>): Promise<string>;
  getRules(): Record<string, string>;
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

  it('reports klm/gen-02-no-duplicate-diagrams on the duplicate diagram fixture', async () => {
    const issues: Array<{ rule: string }> = JSON.parse(await api.lintXml(fixtures.gen02DuplicateDiagram));
    assert.ok(issues.some((i) => i.rule === 'klm/gen-02-no-duplicate-diagrams'));
  });

  it('exposes klm/gen-02-no-duplicate-diagrams in getRules()', () => {
    assert.equal(api.getRules()['klm/gen-02-no-duplicate-diagrams'], 'error');
  });
});
