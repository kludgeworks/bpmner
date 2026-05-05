import { isAny } from 'bpmnlint-utils';
import { getDefinitions, hasAnyAssociatedAnnotation, type ModdleElement, type Reporter } from './_helpers';

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, [ 'bpmn:Task', 'bpmn:SubProcess' ])) {
      return;
    }

    const loopType = node.loopCharacteristics?.$type;

    if (loopType !== 'bpmn:StandardLoopCharacteristics' && loopType !== 'bpmn:MultiInstanceLoopCharacteristics') {
      return;
    }

    const definitions = getDefinitions(node);

    if (!definitions || !hasAnyAssociatedAnnotation(node, definitions)) {
      reporter.report(node.id, 'Loop or multi-instance activity must be linked to a text annotation via association');
    }
  }

  return { check };
};