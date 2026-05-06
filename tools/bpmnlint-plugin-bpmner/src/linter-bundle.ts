// @ts-ignore
import { Linter } from 'bpmnlint';
// @ts-ignore
import BpmnModdle from 'bpmn-moddle';
// @ts-ignore
import { config, resolver } from './static-rules';

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
            ...ruleOverrides
        }
      },
      resolver: resolver
    });
    
    const result = await linter.lint(rootElement);
    const issues = [];
    
    for (const [ rule, reports ] of Object.entries(result || {})) {
      for (const item of (reports as any[]) || []) {
        issues.push({
          id: item.id || item.elementId || item.node?.id || null,
          rule,
          message: item.message,
          category: item.category || 'error',
          ...(item || {})
        });
      }
    }
    
    return JSON.stringify(issues);
  } catch (err: any) {
    return JSON.stringify([{
      rule: 'parse-error',
      message: err.message,
      category: 'error'
    }]);
  }
}

// Expose the API to the global scope for GraalJS/Polyglot
(globalThis as any).BpmnLinterApi = { 
    lintXml,
    getRules: () => config.rules
};
