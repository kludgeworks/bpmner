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

const COMMON_ACRONYMS = new Set([ 'BPMN', 'KLM', 'SLA', 'API', 'IT' ]);

function hasUncommonAbbreviation(name: string): boolean {
  const matches = name.match(/\b[A-Z]{2,}\b/g) || [];

  return matches.some((token) => !COMMON_ACRONYMS.has(token));
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TYPES_WITH_NAMES)) {
      return;
    }

    const name = node.name?.trim() || '';

    if (!name) {
      return;
    }

    if (hasUncommonAbbreviation(name)) {
      reporter.report(node.id, 'Avoid uncommon abbreviations in labels or explain them via annotation/glossary');
    }
  }

  return { check };
};