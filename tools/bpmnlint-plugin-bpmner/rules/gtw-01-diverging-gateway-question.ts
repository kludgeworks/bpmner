import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

const TARGET_TYPES = [ 'bpmn:ExclusiveGateway', 'bpmn:InclusiveGateway' ];

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TARGET_TYPES)) {
      return;
    }

    if ((node.outgoing || []).length <= 1) {
      return;
    }

    const name = node.name?.trim() || '';

    if (!name || !name.endsWith('?')) {
      reporter.report(node.id, 'Diverging exclusive/inclusive gateway should be named as a question');
    }
  }

  return { check };
};
