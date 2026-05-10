import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';
import { getRuleMessage, getStaticConfig } from '../src/rule-config';

const RULE_ID = 'act-03-discouraged-business-verbs';
const TARGET_TYPES = [ 'bpmn:Task', 'bpmn:SubProcess', 'bpmn:CallActivity' ];
const { discouragedLeadingVerbs } = getStaticConfig<{ discouragedLeadingVerbs: string[] }>(RULE_ID);
const DISCOURAGED_LEADING_VERBS = new Set(discouragedLeadingVerbs);
const MESSAGE = getRuleMessage(RULE_ID);

function firstWord(name: string): string {
  const word = name.split(/\s+/)[0] || '';
  return word.toLowerCase().replace(/^[^a-z]+|[^a-z]+$/g, '');
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TARGET_TYPES)) {
      return;
    }

    const name = node.name?.trim() || '';

    if (!name) {
      return;
    }

    if (DISCOURAGED_LEADING_VERBS.has(firstWord(name))) {
      reporter.report(node.id, MESSAGE);
    }
  }

  return { check };
};
