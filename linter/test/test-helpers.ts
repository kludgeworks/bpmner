import BpmnModdle from 'bpmn-moddle';
import { Linter } from 'bpmnlint';
import { resolver } from '../src/static-rules';
import { customRuleManifest, KLM_PLUGIN_PREFIX } from '../src/rule-manifest';

type Report = {
  id?: string;
  message: string;
  category?: string;
};

type LintResults = Record<string, Report[]>;

class PluginResolver {
  resolveRule = resolver.resolveRule;
  resolveConfig = resolver.resolveConfig;
}

const customRuleConfig = {
  rules: Object.fromEntries(
    customRuleManifest.map(({ id, level }) => [`${KLM_PLUGIN_PREFIX}/${id}`, level])
  ),
};

export async function lint(xml: string): Promise<LintResults> {
  const moddle = new BpmnModdle();
  const { rootElement } = await moddle.fromXML(xml);
  const linter = new Linter({
    config: customRuleConfig,
    resolver: new PluginResolver(),
  });

  return linter.lint(rootElement as never) as LintResults;
}

export function reportsFor(results: LintResults, ruleName: string): Report[] {
  const match = Object.entries(results).find(([ key ]) => {
    return key === ruleName || key.endsWith(`/${ruleName}`);
  });

  return match?.[1] || [];
}

export function hasRule(results: LintResults, ruleName: string): boolean {
  return reportsFor(results, ruleName).length > 0;
}
