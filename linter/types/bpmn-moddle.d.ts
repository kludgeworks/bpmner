declare module 'bpmn-moddle' {
  export type ModdleElement = {
    id?: string;
    get?(name: string): unknown;
    set?(name: string, value: unknown): void;
    [key: string]: unknown;
  };

  export default class BpmnModdle {
    fromXML(xml: string): Promise<{ rootElement: ModdleElement }>;
    toXML(rootElement: ModdleElement): Promise<{ xml: string }>;
    create(type: string, attrs?: Record<string, unknown>): ModdleElement;
  }
}
