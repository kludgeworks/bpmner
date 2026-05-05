import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

const TARGET_TYPES = [ 'bpmn:Task', 'bpmn:SubProcess', 'bpmn:CallActivity' ];
const DISCOURAGED_LEADING_VERBS = new Set([ 'handle', 'manage', 'process', 'perform', 'do' ]);

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
      reporter.report(node.id, 'Activity label starts with a discouraged generic verb; prefer a more specific business verb');
    }
  }

  return { check };
};