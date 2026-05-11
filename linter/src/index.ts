import { allPluginRules, pluginRulePaths, recommendedPluginRules } from './rule-manifest';

const plugin = {
  configs: {
    recommended: {
      extends: 'bpmnlint:recommended',
      rules: recommendedPluginRules,
    },
    'recommended-error': {
      extends: 'bpmnlint:recommended-error',
      rules: recommendedPluginRules,
    },
    all: {
      rules: allPluginRules,
    },
  },
  rules: pluginRulePaths,
};

export = plugin;
