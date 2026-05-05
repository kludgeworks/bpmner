import { is } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

const DISCOURAGED_LEADING_VERBS = new Set([
  'send', 'create', 'update', 'approve', 'confirm', 'notify', 'request'
]);

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!is(node, 'bpmn:MessageFlow')) {
      return;
    }

    const name = node.name?.trim();

    if (!name) {
      return;
    }

    const words = name.split(/\s+/);
    const first = words[0].toLowerCase();

    if (DISCOURAGED_LEADING_VERBS.has(first)) {
      reporter.report(node.id, 'Message flow name should describe the message, not an action');
      return;
    }

    const last = words[words.length - 1].toLowerCase();

    if (words.length > 1 && /(ed)$/.test(last) && !/(ed)$/.test(first)) {
      reporter.report(node.id, 'Prefer noun-based message naming (e.g. Confirmed order message)');
    }
  }

  return { check };
};
