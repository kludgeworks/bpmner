import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';

const DISCOURAGED_WORDS = /\b(activity|process|event)\b/i;

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, [ 'bpmn:DataObjectReference', 'bpmn:DataStoreReference' ])) {
      return;
    }

    if (node.name && DISCOURAGED_WORDS.test(node.name)) {
      reporter.report(node.id, 'Data element name must be a business noun phrase, not an element-type label');
    }
  }

  return { check };
};