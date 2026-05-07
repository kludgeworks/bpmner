import { Linter } from 'bpmnlint';
import BpmnModdle from 'bpmn-moddle';
import { config, resolver } from './static-rules';

type LintReport = {
  id?: string;
  elementId?: string;
  node?: { id?: string };
  message: string;
  category?: string;
  [key: string]: unknown;
};

type LintResults = Record<string, LintReport[]>;

/**
 * Lints a BPMN XML string and returns a JSON string of issues.
 * This is designed to be called from GraalJS.
 */
export async function lintXml(xmlString: string, ruleOverrides?: Record<string, string>): Promise<string> {
  const moddle = new BpmnModdle();

  try {
    const { rootElement } = await moddle.fromXML(xmlString);

    const linter = new Linter({
      config: {
        ...config,
        rules: {
          ...config.rules,
          ...ruleOverrides,
        },
      },
      resolver,
    });

    const result = (await linter.lint(rootElement)) as LintResults;
    const issues: Record<string, unknown>[] = [];

    for (const [rule, reports] of Object.entries(result || {})) {
      for (const item of reports || []) {
        issues.push({
          ...item,
          id: item.id || item.elementId || item.node?.id || null,
          rule,
          message: item.message,
          category: item.category || 'error',
        });
      }
    }

    return JSON.stringify(issues);
  } catch (err: unknown) {
    return JSON.stringify([
      {
        rule: 'parse-error',
        message: err instanceof Error ? err.message : String(err),
        category: 'error',
      },
    ]);
  }
}

// Expose the API to the global scope for GraalJS/Polyglot
(globalThis as typeof globalThis & { BpmnLinterApi?: unknown }).BpmnLinterApi = {
  lintXml,
  getRules: () => config.rules,
};
