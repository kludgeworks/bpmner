import { isAny } from 'bpmnlint-utils';
import { getDefinitions, hasAnnotationMatching, type ModdleElement, type Reporter } from './_helpers';

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, [ 'bpmn:Task', 'bpmn:SubProcess' ])) {
      return;
    }

    if (node.loopCharacteristics?.$type !== 'bpmn:StandardLoopCharacteristics') {
      return;
    }

    const definitions = getDefinitions(node);

    if (!definitions || !hasAnnotationMatching(node, definitions, /loop until/i)) {
      reporter.report(node.id, 'Loop activity is missing a linked text annotation with "Loop until ..."');
    }
  }

  return { check };
};