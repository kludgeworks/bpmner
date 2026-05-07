declare module 'bpmnlint' {
  export class Linter {
    constructor(options: unknown);
    lint(rootElement: unknown): Record<string, unknown[]>;
  }
}

declare module 'bpmnlint/rules/*' {
  const ruleFactory: unknown;
  export default ruleFactory;
}
