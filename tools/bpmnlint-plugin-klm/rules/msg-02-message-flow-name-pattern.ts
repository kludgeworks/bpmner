import { is } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';
import winkNLP from 'wink-nlp';
import model from 'wink-eng-lite-web-model';

const nlp = winkNLP(model);
const its = nlp.its;

function startsWithVerbLike(name: string): boolean {
  const doc = nlp.readDoc(name);
  const first = doc.tokens().itemAt(0);

  if (!first) {
    return false;
  }

  const pos = first.out(its.pos);
  return pos === 'VERB' || pos === 'AUX';
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!is(node, 'bpmn:MessageFlow')) {
      return;
    }

    const name = node.name?.trim();

    if (!name) {
      return;
    }

    if (startsWithVerbLike(name)) {
      reporter.report(node.id, 'Message flow name should describe the message, not an action');
    }
  }

  return { check };
};
