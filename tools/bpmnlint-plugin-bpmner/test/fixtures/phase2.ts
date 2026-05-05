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
</bpmn:definitions>`
};
