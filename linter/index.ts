import { pluginRulePaths, recommendedPluginRules } from './src/rule-manifest';

const plugin = {
  configs: {
    recommended: {
      extends: 'bpmnlint:recommended',
      rules: recommendedPluginRules,
    },
  },
  rules: pluginRulePaths,
};

export = plugin;
