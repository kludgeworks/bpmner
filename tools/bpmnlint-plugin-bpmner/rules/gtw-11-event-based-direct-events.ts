import { is } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!is(node, 'bpmn:EventBasedGateway')) {
      return;
    }

    for (const flow of (node.outgoing || [])) {
      const target = flow.targetRef as ModdleElement | undefined;

      if (!target || !is(target, 'bpmn:IntermediateCatchEvent') && !is(target, 'bpmn:ReceiveTask')) {
        reporter.report(node.id, 'Event-based gateway must connect directly to intermediate catch events or receive tasks');
      }
    }
  }

  return { check };
};