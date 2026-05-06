import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

const TYPES_WITH_NAMES = [
  'bpmn:Task',
  'bpmn:SubProcess',
  'bpmn:CallActivity',
  'bpmn:StartEvent',
  'bpmn:IntermediateCatchEvent',
  'bpmn:IntermediateThrowEvent',
  'bpmn:EndEvent',
  'bpmn:ExclusiveGateway',
  'bpmn:InclusiveGateway',
  'bpmn:ParallelGateway',
  'bpmn:ComplexGateway',
  'bpmn:DataObjectReference',
  'bpmn:DataStoreReference'
];

const DISCOURAGED_WORDS = /\b(activity|process|event)\b/i;

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TYPES_WITH_NAMES)) {
      return;
    }

    if (node.name && DISCOURAGED_WORDS.test(node.name)) {
      reporter.report(node.id, 'Element name must not include its BPMN element type');
    }
  }

  return { check };
};