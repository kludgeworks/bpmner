import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';
import winkNLP from 'wink-nlp';
import model from 'wink-eng-lite-web-model';

const TARGET_TYPES = [ 'bpmn:Task', 'bpmn:SubProcess', 'bpmn:CallActivity' ];

const nlp = winkNLP(model);
const its = nlp.its;

function isVerbLike(token: string): boolean {
  const doc = nlp.readDoc(token);
  const first = doc.tokens().itemAt(0);

  if (!first) {
    return false;
  }

  const pos = first.out(its.pos);
  return pos === 'VERB' || pos === 'AUX';
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TARGET_TYPES)) {
      return;
    }

    const rawName = node.name?.trim();

    if (!rawName) {
      return;
    }

    const parts = rawName.split(/\s+/);

    if (parts.length < 2) {
      reporter.report(node.id, 'Activity name should follow Verb + Object (at least two words)');
      return;
    }

    if (isVerbLike(parts[0])) {
      return;
    }

    reporter.report(node.id, 'Activity name should start with a business verb');
  }

  return { check };
};
