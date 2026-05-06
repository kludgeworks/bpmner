const fs = require('node:fs');
const path = require('node:path');

// 1. Read the bundle
const bundlePath = path.join(__dirname, 'dist', 'bpmnlint-bundle.js');
const bundleSource = fs.readFileSync(bundlePath, 'utf8');

// 2. Mock globalThis and execute bundle
// In GraalJS, this happens automatically, but in Node we need to evaluate it.
const mockGlobal = {
  globalThis: {}
};
mockGlobal.globalThis = mockGlobal;

// Use eval to simulate GraalJS environment
const script = new Function('globalThis', bundleSource);
script(mockGlobal);

const BpmnLinterApi = mockGlobal.BpmnLinterApi;

if (!BpmnLinterApi) {
  console.error('FAILED: BpmnLinterApi not found in bundle');
  process.exit(1);
}

const testXml = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  id="Definitions_1"
  targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1" isExecutable="false">
    <bpmn:startEvent id="StartEvent_1" name="Request received">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:task id="Task_1" name="Validate request">
      <bpmn:incoming>Flow_1</bpmn:incoming>
    </bpmn:task>
  </bpmn:process>
</bpmn:definitions>`;

async function runTest() {
  console.log('Running bundle test...');
  try {
    const resultJson = await BpmnLinterApi.lintXml(testXml);
    const issues = JSON.parse(resultJson);
    
    console.log('Linting successful. Issues found:', issues.length);
    
    // We expect at least 'end-event-required' issue in the sample XML
    const hasEndEventIssue = issues.some(i => i.rule === 'bpmnlint/end-event-required');
    
    if (hasEndEventIssue) {
      console.log('SUCCESS: Bundle correctly identified expected issues.');
    } else {
      console.warn('WARNING: Did not find expected end-event-required issue. Issues found:', JSON.stringify(issues, null, 2));
    }
    
    // Test the new KLM rule (duplicate diagrams)
    const duplicateDiagramXml = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" 
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" 
  id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1" />
  <bpmndi:BPMNDiagram id="Diagram_1"><bpmndi:BPMNPlane id="Plane_1" bpmnElement="Process_1" /></bpmndi:BPMNDiagram>
  <bpmndi:BPMNDiagram id="Diagram_2"><bpmndi:BPMNPlane id="Plane_2" bpmnElement="Process_1" /></bpmndi:BPMNDiagram>
</bpmn:definitions>`;

    const dupResultJson = await BpmnLinterApi.lintXml(duplicateDiagramXml);
    const dupIssues = JSON.parse(dupResultJson);
    const hasDupDiagramIssue = dupIssues.some(i => i.rule === 'klm/gen-02-no-duplicate-diagrams');
    
    if (hasDupDiagramIssue) {
      console.log('SUCCESS: Bundle correctly identified KLM gen-02 duplicate diagram issue.');
    } else {
      console.error('FAILED: Did not find KLM gen-02 issue in bad XML. Issues found:', JSON.stringify(dupIssues, null, 2));
      process.exit(1);
    }

  } catch (err) {
    console.error('ERROR during bundle execution:', err);
    process.exit(1);
  }
}

runTest();
