import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';
import { getRuleMessage } from '../rule-config';
import winkNLP from 'wink-nlp';
import model from 'wink-eng-lite-web-model';

const RULE_ID = 'act-01-verb-object-name';
const TARGET_TYPES = [ 'bpmn:Task', 'bpmn:SubProcess', 'bpmn:CallActivity' ];
const TOO_SHORT_MESSAGE = getRuleMessage(RULE_ID, 'tooShort');
const MISSING_VERB_MESSAGE = getRuleMessage(RULE_ID, 'missingVerb');

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
      reporter.report(node.id, TOO_SHORT_MESSAGE);
      return;
    }

    if (isVerbLike(parts[0])) {
      return;
    }

    reporter.report(node.id, MISSING_VERB_MESSAGE);
  }

  return { check };
};
