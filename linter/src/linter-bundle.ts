import { Linter } from 'bpmnlint';
import BpmnModdle from 'bpmn-moddle';
import { configs, customRuleDocs, resolver } from './generated/static-rules';

type RuleLevel = 'off' | 'warn' | 'error';

type LintConfig = {
  extends?: string | string[];
  rules?: Record<string, RuleLevel>;
};

type LintReport = {
  id?: string;
  elementId?: string;
  node?: { id?: string };
  message: string;
  category?: string;
  [key: string]: unknown;
};

type LintResults = Record<string, LintReport[]>;

const DEFAULT_LINT_CONFIG: LintConfig = {
  extends: ['bpmnlint:recommended'],
};

function normalizeConfigInput(configInput?: unknown, includeDefaults = true): LintConfig {
  if (configInput == null) {
    return includeDefaults ? DEFAULT_LINT_CONFIG : {};
  }

  if (typeof configInput === 'string') {
    return {
      ...(includeDefaults ? DEFAULT_LINT_CONFIG : {}),
      ...JSON.parse(configInput),
    } as LintConfig;
  }

  if (typeof configInput === 'object') {
    return {
      ...(includeDefaults ? DEFAULT_LINT_CONFIG : {}),
      ...(configInput as LintConfig),
    };
  }

  return includeDefaults ? DEFAULT_LINT_CONFIG : {};
}

function toArray(value?: string | string[]): string[] {
  if (!value) {
    return [];
  }
  return Array.isArray(value) ? value : [value];
}

function normalizeRuleName(ruleName: string): string {
  if (ruleName.startsWith('bpmnlint-plugin-bpmner/')) {
    return `bpmner/${ruleName.slice('bpmnlint-plugin-bpmner/'.length)}`;
  }
  return ruleName;
}

function configRefKey(configRef: string): string {
  if (configRef.startsWith('plugin:')) {
    return configRef;
  }
  return configRef;
}

function expandPluginRuleNames(pkg: string, rules: Record<string, RuleLevel>): Record<string, RuleLevel> {
  const prefix = pkg === 'bpmnlint-plugin-bpmner' ? 'bpmner' : pkg;

  return Object.fromEntries(
    Object.entries(rules).map(([ruleName, level]) => [
      ruleName.includes('/') ? normalizeRuleName(ruleName) : `${prefix}/${ruleName}`,
      level,
    ])
  );
}

function resolveConfigReference(configRef: string): LintConfig {
  const key = configRefKey(configRef);
  const config = configs[key as keyof typeof configs] as LintConfig | undefined;

  if (config) {
    if (key.startsWith('plugin:')) {
      const pluginName = key.slice('plugin:'.length).split('/')[0];
      return {
        extends: config.extends,
        rules: expandPluginRuleNames(pluginName, config.rules ?? {}),
      };
    }

    return config;
  }

  const match = key.match(/^plugin:([^/]+)\/(.+)$/);
  if (match) {
    return resolver.resolveConfig(match[1], match[2]) as LintConfig;
  }

  const coreMatch = key.match(/^([^:]+):(.+)$/);
  if (coreMatch) {
    return resolver.resolveConfig(coreMatch[1], coreMatch[2]) as LintConfig;
  }

  throw new Error(`Unsupported lint config reference: ${configRef}`);
}

function resolveLintConfig(configInput?: unknown, includeDefaults = true): LintConfig {
  const normalized = normalizeConfigInput(configInput, includeDefaults);
  const mergedRules: Record<string, RuleLevel> = {};

  for (const configRef of toArray(normalized.extends)) {
    const resolved = resolveConfigReference(configRef);
    Object.assign(mergedRules, resolveLintConfig(resolved, false).rules ?? {});
  }

  Object.assign(mergedRules, normalized.rules ?? {});

  return {
    rules: mergedRules,
  };
}

function getRuleDocs(ruleNames: string[]): Record<string, string> {
  const docs: Record<string, string> = {};

  for (const ruleName of ruleNames) {
    const normalized = normalizeRuleName(ruleName);
    const doc = customRuleDocs[normalized as keyof typeof customRuleDocs];
    if (doc) {
      docs[normalized] = doc;
    }
  }

  return docs;
}

/**
 * Lints a BPMN XML string and returns a JSON string of issues.
 * This is designed to be called from GraalJS.
 */
export async function lintXml(xmlString: string, configInput?: unknown): Promise<string> {
  const moddle = new BpmnModdle();
  const resolvedConfig = resolveLintConfig(configInput);

  try {
    const { rootElement } = await moddle.fromXML(xmlString);

    const linter = new Linter({
      config: resolvedConfig,
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
  getDefaultConfig: () => DEFAULT_LINT_CONFIG,
  getRules: (configInput?: unknown) => resolveLintConfig(configInput).rules ?? {},
  getRuleDocs: (ruleNamesInput?: unknown) => {
    if (typeof ruleNamesInput === 'string') {
      return getRuleDocs(JSON.parse(ruleNamesInput) as string[]);
    }

    if (Array.isArray(ruleNamesInput)) {
      return getRuleDocs(ruleNamesInput as string[]);
    }

    return {};
  },
};
