const assert = require('node:assert/strict');
import { phase1Fixtures } from './fixtures/phase1';

async function main() {
  require('../src/linter-bundle');

  const api = (globalThis as typeof globalThis & {
    BpmnLinterApi?: {
      lintXml(xml: string, ruleOverrides?: Record<string, string>): Promise<string>;
      getRules(): Record<string, string>;
    };
  }).BpmnLinterApi;

  assert.ok(api, 'BpmnLinterApi should be defined on globalThis');

  if (!api) {
    throw new Error('BpmnLinterApi is unavailable');
  }

  const builtInIssues = JSON.parse(await api.lintXml(`<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
  <bpmn:process id="Process_1" />
</bpmn:definitions>`));

  assert.equal(builtInIssues.some((issue: { rule: string }) => issue.rule === 'start-event-required'), true);

  const customIssues = JSON.parse(await api.lintXml(phase1Fixtures.gen02DuplicateDiagram));

  assert.equal(customIssues.some((issue: { rule: string }) => issue.rule === 'klm/gen-02-no-duplicate-diagrams'), true);
  assert.equal(api.getRules()['klm/gen-02-no-duplicate-diagrams'], 'error');
}

void main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
