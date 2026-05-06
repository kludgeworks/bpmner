import { is } from 'bpmnlint-utils';
import { getDefinitions, getOwningProcess, type ModdleElement, type Reporter } from './_helpers';

function getErrorRefKey(node: ModdleElement): string {
  const errorDefinition = (node.eventDefinitions || []).find((def) => is(def, 'bpmn:ErrorEventDefinition'));
  const errorRef = errorDefinition?.errorRef as ModdleElement | undefined;

  return errorRef?.id || errorRef?.name || (errorDefinition?.name as string | undefined) || node.name || '';
}

export = function() {
  function check(node: ModdleElement, reporter: Reporter) {
    if (!is(node, 'bpmn:EndEvent')) {
      return;
    }

    const hasErrorDefinition = (node.eventDefinitions || []).some((def) => is(def, 'bpmn:ErrorEventDefinition'));

    if (!hasErrorDefinition) {
      return;
    }

    const scope = node.$parent;

    if (!scope || !is(scope, 'bpmn:SubProcess')) {
      reporter.report(node.id, 'Error end event must be placed inside a subprocess');
      return;
    }

    const parentProcess = getOwningProcess(scope);
    const definitions = getDefinitions(node);

    if (!parentProcess || !definitions) {
      return;
    }

    const expectedKey = getErrorRefKey(node);
    const matchingBoundary = (parentProcess.flowElements || []).find((element) => {
      if (!is(element, 'bpmn:BoundaryEvent') || element.attachedToRef !== scope) {
        return false;
      }

      const boundaryHasError = (element.eventDefinitions || []).some((def) => is(def, 'bpmn:ErrorEventDefinition'));

      if (!boundaryHasError) {
        return false;
      }

      return getErrorRefKey(element) === expectedKey;
    });

    if (!matchingBoundary) {
      reporter.report(node.id, 'Error end event must match an error boundary event on its parent subprocess');
    }
  }

  return { check };
};