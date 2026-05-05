import { is, isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!is(node, 'bpmn:BoundaryEvent')) {
      return;
    }

    if (!node.attachedToRef || !isAny(node.attachedToRef, [ 'bpmn:Task', 'bpmn:SubProcess' ])) {
      reporter.report(node.id, 'Boundary event must be attached to a task or subprocess');
    }

    if ((node.incoming || []).length > 0) {
      reporter.report(node.id, 'Boundary event must not have incoming sequence flow');
    }

    if ((node.outgoing || []).length !== 1) {
      reporter.report(node.id, 'Boundary event must have exactly one outgoing sequence flow');
    }

    const hasErrorDefinition = (node.eventDefinitions || []).some((def) => is(def, 'bpmn:ErrorEventDefinition'));

    if (hasErrorDefinition && node.cancelActivity !== true) {
      reporter.report(node.id, 'Error boundary event must be interrupting');
    }
  }

  return { check };
};