import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';
import { getRuleConfig, getRuleMessage, getStaticConfig } from '../src/rule-config';

const RULE_ID = 'name-02-uncommon-abbreviations';
const TYPES_WITH_NAMES = getRuleConfig(RULE_ID).targetElements || [
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

const { commonAcronyms } = getStaticConfig<{ commonAcronyms: string[] }>(RULE_ID);
const COMMON_ACRONYMS = new Set(commonAcronyms);
const MESSAGE = getRuleMessage(RULE_ID);

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
      reporter.report(node.id, MESSAGE);
    }
  }

  return { check };
};
