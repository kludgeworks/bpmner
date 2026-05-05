import { isAny } from 'bpmnlint-utils';
import type { ModdleElement, Reporter } from './_helpers';
import winkNLP from 'wink-nlp';
import type { ItemToken } from 'wink-nlp';
import model from 'wink-eng-lite-web-model';

const TARGET_TYPES = [
  'bpmn:StartEvent',
  'bpmn:IntermediateCatchEvent',
  'bpmn:IntermediateThrowEvent',
  'bpmn:EndEvent'
];

const nlp = winkNLP(model);
const its = nlp.its;

const STATE_WORDS = new Set([
  'received', 'approved', 'rejected', 'completed', 'confirmed',
  'sent', 'fulfilled', 'failed', 'cancelled', 'resolved', 'closed'
]);

type TokenView = {
  text: string;
  pos: string;
};

function tokenize(name: string): TokenView[] {
  const doc = nlp.readDoc(name);
  const tokens: TokenView[] = [];

  doc.tokens().each((token: ItemToken) => {
    tokens.push({
      text: token.out().toLowerCase(),
      pos: token.out(its.pos)
    });
  });

  return tokens.filter((token) => /[a-z]/i.test(token.text));
}

function hasStatePattern(name: string): boolean {
  const tokens = tokenize(name);

  if (tokens.length < 2) {
    return false;
  }

  const hasNoun = tokens.some((token) => token.pos === 'NOUN' || token.pos === 'PROPN');
  const hasState = tokens.some((token) => {
    return token.pos === 'ADJ' || token.pos === 'VERB' || token.text.endsWith('ed') || STATE_WORDS.has(token.text);
  });

  return hasNoun && hasState;
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, TARGET_TYPES)) {
      return;
    }

    const name = node.name?.trim() || '';

    if (!name) {
      return;
    }

    if (!hasStatePattern(name)) {
      reporter.report(node.id, 'Event name should follow a noun + state/result pattern (e.g. Request approved)');
    }
  }

  return { check };
};