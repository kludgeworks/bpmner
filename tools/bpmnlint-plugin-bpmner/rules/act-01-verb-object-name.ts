import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

const TARGET_TYPES = [ 'bpmn:Task', 'bpmn:SubProcess', 'bpmn:CallActivity' ];

const KNOWN_VERBS = new Set([
  'accept', 'approve', 'assign', 'calculate', 'check', 'close', 'collect', 'confirm',
  'create', 'dispatch', 'evaluate', 'inform', 'open', 'prepare', 'receive', 'record',
  'register', 'reject', 'review', 'schedule', 'send', 'start', 'stop', 'submit',
  'update', 'validate', 'verify'
]);

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TARGET_TYPES)) {
      return;
    }

    const rawName = node.name?.trim();

    if (!rawName) {
      return;
    }

    const parts = rawName.split(/\s+/);

    if (parts.length < 2) {
      reporter.report(node.id, 'Activity name should follow Verb + Object (at least two words)');
      return;
    }

    const first = parts[0].toLowerCase();

    if (KNOWN_VERBS.has(first)) {
      return;
    }

    if (/(ing|tion|ment|ance|ence)$/.test(first)) {
      reporter.report(node.id, 'Activity name looks noun-based; prefer Verb + Object naming');
      return;
    }

    reporter.report(node.id, 'Activity name should start with a business verb');
  }

  return { check };
};
