declare module 'bpmnlint' {
  export class Linter {
    constructor(options: unknown);
    lint(rootElement: unknown): Record<string, unknown[]>;
  }
}