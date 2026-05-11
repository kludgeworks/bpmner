import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

const TARGET_TYPES = [ 'bpmn:Task', 'bpmn:SubProcess', 'bpmn:CallActivity' ];

function isAllCaps(word: string): boolean {
  return /^[A-Z0-9]+$/.test(word) && /[A-Z]/.test(word);
}

function startsWithUpper(word: string): boolean {
  return /^[A-Z]/.test(word);
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TARGET_TYPES)) {
      return;
    }

    const rawName = node.name?.trim() || '';

    if (!rawName) {
      return;
    }

    const words = rawName.split(/\s+/);
    const first = words[0];

    if (!startsWithUpper(first) && !isAllCaps(first)) {
      reporter.report(node.id, 'Activity label should start with a capitalized first word');
      return;
    }

    for (const word of words.slice(1)) {
      if (isAllCaps(word)) {
        continue;
      }

      if (startsWithUpper(word)) {
        reporter.report(node.id, 'Activity label should use sentence case after the first word (except acronyms/proper nouns)');
        return;
      }
    }
  }

  return { check };
};