import { isAny } from 'bpmnlint-utils';
import { getDefinitions, hasAnnotationMatching, type ModdleElement, type Reporter } from './_helpers';

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, [ 'bpmn:Task', 'bpmn:SubProcess' ])) {
      return;
    }

    if (node.loopCharacteristics?.$type !== 'bpmn:MultiInstanceLoopCharacteristics') {
      return;
    }

    const definitions = getDefinitions(node);

    if (!definitions || !hasAnnotationMatching(node, definitions, /for each/i)) {
      reporter.report(node.id, 'Multi-instance activity is missing a linked text annotation with "For each ..."');
    }
  }

  return { check };
};