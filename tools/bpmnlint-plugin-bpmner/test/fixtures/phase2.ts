export const phase2Fixtures = {
  act01Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Order creation" />
  </bpmn:process>
</bpmn:definitions>`,
  act01Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Create order" />
  </bpmn:process>
</bpmn:definitions>`,
  act01PhrasalVerbValid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Set up workstation" />
  </bpmn:process>
</bpmn:definitions>`,
  act01UppercaseLabelValid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="CREATE order" />
  </bpmn:process>
</bpmn:definitions>`,
  act02Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="receive Customer request" />
  </bpmn:process>
</bpmn:definitions>`,
  act02Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Receive customer request" />
  </bpmn:process>
</bpmn:definitions>`,
  act03Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Handle passenger request" />
  </bpmn:process>
</bpmn:definitions>`,
  act03Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Validate passenger request" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw01Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1" name="Document check result">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" name="Valid" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Invalid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw01Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1" name="Is document valid?">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" name="Valid" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Invalid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw01ValidNoQuestionMark: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1" name="Is document valid">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" name="Valid" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Invalid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw02Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:outgoing>Flow_2</bpmn:outgoing></bpmn:task>
    <bpmn:exclusiveGateway id="Gateway_1" name="Decision merged">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:incoming>Flow_2</bpmn:incoming>
      <bpmn:outgoing>Flow_3</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Task_1" targetRef="Gateway_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_2" targetRef="Gateway_1" />
    <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_1" targetRef="EndEvent_1" />
  </bpmn:process>
</bpmn:definitions>`,
  flow02Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1" name="Is request valid?">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Valid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  flow02Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1" name="Is request valid?">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" name="Invalid" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Valid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  evt13Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:intermediateCatchEvent id="Event_1" name="Approve request" />
  </bpmn:process>
</bpmn:definitions>`,
  evt13Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:intermediateCatchEvent id="Event_1" name="Request approved" />
  </bpmn:process>
</bpmn:definitions>`,
  evt01Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:endEvent id="EndEvent_1" name="Approve request" />
  </bpmn:process>
</bpmn:definitions>`,
  evt01Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:endEvent id="EndEvent_1" name="Request approved" />
  </bpmn:process>
</bpmn:definitions>`,
  evt02Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:intermediateCatchEvent id="Event_1" name="Approval" />
  </bpmn:process>
</bpmn:definitions>`,
  evt02Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:intermediateCatchEvent id="Event_1" name="Approval received" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw02Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:outgoing>Flow_2</bpmn:outgoing></bpmn:task>
    <bpmn:exclusiveGateway id="Gateway_1">
      <bpmn:incoming>Flow_1</bpmn:incoming>
      <bpmn:incoming>Flow_2</bpmn:incoming>
      <bpmn:outgoing>Flow_3</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:endEvent id="EndEvent_1"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Task_1" targetRef="Gateway_1" />
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_2" targetRef="Gateway_1" />
    <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_1" targetRef="EndEvent_1" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw03Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1" name="Check document and approve application?">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" name="Valid" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Invalid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  gtw03Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:exclusiveGateway id="Gateway_1" name="Is document valid?">
      <bpmn:outgoing>Flow_1</bpmn:outgoing>
      <bpmn:outgoing>Flow_2</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:task id="Task_1"><bpmn:incoming>Flow_1</bpmn:incoming></bpmn:task>
    <bpmn:task id="Task_2"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:task>
    <bpmn:sequenceFlow id="Flow_1" name="Valid" sourceRef="Gateway_1" targetRef="Task_1" />
    <bpmn:sequenceFlow id="Flow_2" name="Invalid" sourceRef="Gateway_1" targetRef="Task_2" />
  </bpmn:process>
</bpmn:definitions>`,
  msg02Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_A"><bpmn:task id="Task_A" /></bpmn:process>
  <bpmn:process id="Process_B"><bpmn:task id="Task_B" /></bpmn:process>
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="Participant_A" processRef="Process_A" />
    <bpmn:participant id="Participant_B" processRef="Process_B" />
    <bpmn:messageFlow id="MessageFlow_1" name="Send approval" sourceRef="Task_A" targetRef="Task_B" />
  </bpmn:collaboration>
</bpmn:definitions>`,
  msg02Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_A"><bpmn:task id="Task_A" /></bpmn:process>
  <bpmn:process id="Process_B"><bpmn:task id="Task_B" /></bpmn:process>
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="Participant_A" processRef="Process_A" />
    <bpmn:participant id="Participant_B" processRef="Process_B" />
    <bpmn:messageFlow id="MessageFlow_1" name="Approval confirmation" sourceRef="Task_A" targetRef="Task_B" />
  </bpmn:collaboration>
</bpmn:definitions>`,
  msg02UppercaseVerbInvalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_A"><bpmn:task id="Task_A" /></bpmn:process>
  <bpmn:process id="Process_B"><bpmn:task id="Task_B" /></bpmn:process>
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="Participant_A" processRef="Process_A" />
    <bpmn:participant id="Participant_B" processRef="Process_B" />
    <bpmn:messageFlow id="MessageFlow_1" name="SEND approval" sourceRef="Task_A" targetRef="Task_B" />
  </bpmn:collaboration>
</bpmn:definitions>`,
  msg02PastParticipleNounValid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_A"><bpmn:task id="Task_A" /></bpmn:process>
  <bpmn:process id="Process_B"><bpmn:task id="Task_B" /></bpmn:process>
  <bpmn:collaboration id="Collab_1">
    <bpmn:participant id="Participant_A" processRef="Process_A" />
    <bpmn:participant id="Participant_B" processRef="Process_B" />
    <bpmn:messageFlow id="MessageFlow_1" name="Order approved message" sourceRef="Task_A" targetRef="Task_B" />
  </bpmn:collaboration>
</bpmn:definitions>`,
  name02Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Clear ITBL pax" />
  </bpmn:process>
</bpmn:definitions>`,
  name02Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Clear passengers from BPMNER list" />
  </bpmn:process>
</bpmn:definitions>`,
  name01Invalid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="PROC_123_REQ_SYNC" />
  </bpmn:process>
</bpmn:definitions>`,
  name01Valid: `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1" targetNamespace="http://example.com/bpmn">
  <bpmn:process id="Process_1">
    <bpmn:task id="Task_1" name="Passenger rebooking request" />
  </bpmn:process>
</bpmn:definitions>`
};
