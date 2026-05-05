declare module 'bpmn-moddle' {
  export default class BpmnModdle {
    fromXML(xml: string): Promise<{ rootElement: unknown }>;
  }
}