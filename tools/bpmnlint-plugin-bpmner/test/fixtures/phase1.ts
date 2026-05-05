export const phase1Fixtures = {
  validBaseline: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
  xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"
  xmlns:di="http://www.omg.org/spec/DD/20100524/DI"
  id="Definitions_1"
  targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1" isExecutable="false">
    <bpmn:startEvent id="StartEvent_1" name="Request received">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:task id="Task_1" name="Validate request">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="EndEvent_1" name="Request completed">
      <bpmn:incoming>Flow_2</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1" />
  </bpmn:process>
</bpmn:definitions>`,
  gen01Choreography: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:choreography id="Choreography_1" name="Some choreography" />
</bpmn:definitions>`,
  act12LoopWithoutAnnotation: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:task id="Task_1" name="Repeat check">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
      <bpmn:standardLoopCharacteristics />
    </bpmn:task>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1" />
  </bpmn:process>
</bpmn:definitions>`,
  act13MiWithoutAnnotation: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:task id="Task_1" name="Review passenger">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
      <bpmn:multiInstanceLoopCharacteristics isSequential="true" />
    </bpmn:task>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="EndEvent_1" />
  </bpmn:process>
</bpmn:definitions>`,
  act12LoopWithEquivalentAnnotation: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Repeat check">
      <bpmn:standardLoopCharacteristics />
    </bpmn:task>
    <bpmn:textAnnotation id="TextAnnotation_1">
      <bpmn:text>Repeat verification until all checks pass</bpmn:text>
    </bpmn:textAnnotation>
    <bpmn:association id="Association_1" sourceRef="Task_1" targetRef="TextAnnotation_1" />
  </bpmn:process>
</bpmn:definitions>`,
  act13MiWithEquivalentAnnotation: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Review passenger">
      <bpmn:multiInstanceLoopCharacteristics isSequential="true" />
    </bpmn:task>
    <bpmn:textAnnotation id="TextAnnotation_1">
      <bpmn:text>For every passenger in the manifest</bpmn:text>
    </bpmn:textAnnotation>
    <bpmn:association id="Association_1" sourceRef="Task_1" targetRef="TextAnnotation_1" />
  </bpmn:process>
</bpmn:definitions>`,
  evt10StartWithIncoming: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:startEvent id="StartEvent_1">
      <bpmn:incoming>Flow_0</bpmn:incoming>
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_0" sourceRef="Task_1" targetRef="StartEvent_1" />
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1" />
  </bpmn:process>
</bpmn:definitions>`,
  evt11MessageStartWithoutMessageFlow: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:startEvent id="StartEvent_1" name="Order received">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:messageEventDefinition id="MessageDef_1" />
    </bpmn:startEvent>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Task_1" />
  </bpmn:process>
</bpmn:definitions>`,
  evt14InvalidBoundary: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" />
    <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:boundaryEvent id="Boundary_1">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:errorEventDefinition id="ErrDef_1" />
    </bpmn:boundaryEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="Boundary_1" />
  </bpmn:process>
</bpmn:definitions>`,
  evt15UnmatchedErrorEnd: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:error id="Error_1" name="Validation error" errorCode="VAL_1" />
  <bpmn:process id="Process_1">
    <bpmn:subProcess id="SubProcess_1">
      <bpmn:startEvent id="StartEvent_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
      <bpmn:endEvent id="EndEvent_1">
        <bpmn:incoming>Flow_1</bpmn:incoming>
        <bpmn:errorEventDefinition id="ErrDef_1" errorRef="Error_1" />
      </bpmn:endEvent>
      <bpmn:sequenceFlow id="Flow_1" sourceRef="StartEvent_1" targetRef="EndEvent_1" />
    </bpmn:subProcess>
  </bpmn:process>
</bpmn:definitions>`,
  evt16UnpairedLink: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:intermediateThrowEvent id="Throw_1" name="L1">
      <bpmn:linkEventDefinition id="LinkDef_1" />
    </bpmn:intermediateThrowEvent>
  </bpmn:process>
</bpmn:definitions>`,
  gtw11EventBasedToTask: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:eventBasedGateway id="Gateway_1">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
    </bpmn:eventBasedGateway>
    <bpmn:task id="Task_1">
      <bpmn:incoming>Flow_1</bpmn:incoming>
    </bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_1" targetRef="Task_1" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw12UnnamedDivergingFlow: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Valid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  flow01CrossPoolSequence: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_A">
    <bpmn:task id="Task_A" />
  </bpmn:process>
  <bpmn:process id="Process_B">
    <bpmn:task id="Task_B" />
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Task_A" targetRef="Task_B" />
  </bpmn:process>
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="Participant_A" processRef="Process_A" />
    <bpmn:participant id="Participant_B" processRef="Process_B" />
  </bpmn:collaboration>
</bpmn:definitions>`,
  msg01SamePoolMessage: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_A">
    <bpmn:task id="Task_A" />
    <bpmn:task id="Task_B" />
  </bpmn:process>
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="Participant_A" processRef="Process_A" />
    <bpmn:messageFlow id="MessageFlow_1" sourceRef="Task_A" targetRef="Task_B" />
  </bpmn:collaboration>
</bpmn:definitions>`,
  assoc01LoopWithoutAssociation: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Repeat review">
      <bpmn:standardLoopCharacteristics />
    </bpmn:task>
  </bpmn:process>
</bpmn:definitions>`,
  data01TypeWordsInDataName: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:dataObjectReference id="Data_1" name="Approval process" dataObjectRef="DataObject_1" />
    <bpmn:dataObject id="DataObject_1" />
  </bpmn:process>
</bpmn:definitions>`,
  name03TypeWordsInElementName: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Review event" />
  </bpmn:process>
</bpmn:definitions>`,
  gen02DuplicateDiagram: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" 
  xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" 
  id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1" />
  <bpmndi:BPMNDiagram id="Diagram_1">
    <bpmndi:BPMNPlane id="Plane_1" bpmnElement="Process_1" />
  </bpmndi:BPMNDiagram>
  <bpmndi:BPMNDiagram id="Diagram_2">
    <bpmndi:BPMNPlane id="Plane_2" bpmnElement="Process_1" />
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`
};
