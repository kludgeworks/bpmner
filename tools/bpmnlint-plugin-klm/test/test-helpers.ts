import BpmnModdle from 'bpmn-moddle';
import { Linter } from 'bpmnlint';
import plugin = require('../index');

type Report = {
  id?: string;
  message: string;
  category?: string;
};

type LintResults = Record<string, Report[]>;

class PluginResolver {
  resolveRule(pkg: string, ruleName: string) {
    if (pkg !== 'bpmnlint-plugin-klm') {
      throw new Error(`Unexpected package <${pkg}>`);
    }

    const rulePath = (plugin.rules as Record<string, string>)[ruleName];

    if (!rulePath) {
      throw new Error(`Unknown rule <${ruleName}>`);
    }

    return require(`../${rulePath.replace(/^\.\//, '')}`);
  }

  resolveConfig(pkg: string, configName: string) {
    if (pkg === 'bpmnlint' && configName === 'recommended') {
      return { rules: {} };
    }

    if (pkg !== 'bpmnlint-plugin-klm') {
      throw new Error(`Unexpected package <${pkg}>`);
    }

    const config = (plugin.configs as Record<string, unknown>)[configName];

    if (!config) {
      throw new Error(`Unknown config <${configName}>`);
    }

    return config;
  }
}

export async function lint(xml: string): Promise<LintResults> {
  const moddle = new BpmnModdle();
  const { rootElement } = await moddle.fromXML(xml);
  const linter = new Linter({
    config: { extends: 'plugin:klm/recommended' },
    resolver: new PluginResolver()
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
