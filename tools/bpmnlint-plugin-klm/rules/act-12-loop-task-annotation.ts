import { isAny } from 'bpmnlint-utils';
import { getAssociatedAnnotationTexts, getDefinitions, type ModdleElement, type Reporter } from './_helpers';
import winkNLP from 'wink-nlp';
import type { ItemToken } from 'wink-nlp';
import model from 'wink-eng-lite-web-model';

const nlp = winkNLP(model);

const LOOP_WORDS = new Set([
  'loop', 'loops', 'looping',
  'repeat', 'repeats', 'repeating',
  'retry', 'retries', 'retrying',
  'rework', 'reworks', 'reworking'
]);
const CONDITION_WORDS = new Set([ 'until', 'while', 'unless', 'till' ]);

function toNormalizedWords(text: string): string[] {
  const doc = nlp.readDoc(text);
  const words: string[] = [];

  doc.tokens().each((token: ItemToken) => {
    const tokenText = token.out();

    if (tokenText) {
      words.push(String(tokenText).toLowerCase());
    }
  });

  return words;
}

function hasLoopConditionIntent(text: string): boolean {
  const words = toNormalizedWords(text);

  const hasLoopSignal = words.some((word) => LOOP_WORDS.has(word));
  const hasConditionSignal = words.some((word) => CONDITION_WORDS.has(word));

  return hasLoopSignal && hasConditionSignal;
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!isAny(node, [ 'bpmn:Task', 'bpmn:SubProcess' ])) {
      return;
    }

    if (node.loopCharacteristics?.$type !== 'bpmn:StandardLoopCharacteristics') {
      return;
    }

    const definitions = getDefinitions(node);

    if (!definitions) {
      return;
    }

    const annotationTexts = getAssociatedAnnotationTexts(node, definitions);
    const hasIntent = annotationTexts.some((text) => hasLoopConditionIntent(text));

    if (!hasIntent) {
      reporter.report(node.id, 'Loop activity is missing a linked text annotation expressing loop-until intent');
    }
  }

  return { check };
};