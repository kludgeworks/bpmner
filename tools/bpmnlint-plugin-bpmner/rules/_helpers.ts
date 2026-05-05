import { is, isAny } from 'bpmnlint-utils';

export type ModdleElement = {
  id?: string;
  $type?: string;
  $parent?: ModdleElement | null;
  rootElements?: ModdleElement[];
  participants?: ModdleElement[];
  processRef?: ModdleElement;
  messageFlows?: ModdleElement[];
  flowElements?: ModdleElement[];
  artifacts?: ModdleElement[];
  sourceRef?: ModdleElement;
  targetRef?: ModdleElement;
  eventDefinitions?: ModdleElement[];
  incoming?: ModdleElement[];
  outgoing?: ModdleElement[];
  attachedToRef?: ModdleElement;
  loopCharacteristics?: ModdleElement;
  text?: string | { body?: string };
  name?: string;
  cancelActivity?: boolean;
  [key: string]: unknown;
};

export type Reporter = {
  report: (id: string | undefined, message: string) => void;
};

export function getDefinitions(node: ModdleElement): ModdleElement | null {
  let current: ModdleElement | undefined | null = node;

  while (current && current.$parent) {
    current = current.$parent;
  }

  return current && is(current, 'bpmn:Definitions') ? current : null;
}

function getCollaborations(definitions: ModdleElement): ModdleElement[] {
  return (definitions.rootElements || []).filter((root) => is(root, 'bpmn:Collaboration'));
}

export function getMessageFlows(definitions: ModdleElement): ModdleElement[] {
  const flows: ModdleElement[] = [];

  for (const collaboration of getCollaborations(definitions)) {
    flows.push(...((collaboration.messageFlows as ModdleElement[]) || []));
  }

  return flows;
}

function getProcessToParticipantMap(definitions: ModdleElement): Map<string, string> {
  const map = new Map<string, string>();

  for (const collaboration of getCollaborations(definitions)) {
    for (const participant of ((collaboration.participants as ModdleElement[]) || [])) {
      if (participant.processRef?.id && participant.id) {
        map.set(participant.processRef.id, participant.id);
      }
    }
  }

  return map;
}

export function getOwningProcess(node: ModdleElement): ModdleElement | null {
  let current: ModdleElement | undefined | null = node;

  while (current) {
    if (is(current, 'bpmn:Process')) {
      return current;
    }

    current = current.$parent;
  }

  return null;
}

export function getPoolIdForNode(node: ModdleElement | undefined, definitions: ModdleElement): string | null {
  if (!node) {
    return null;
  }

  if (is(node, 'bpmn:Participant')) {
    return node.id || null;
  }

  const processMap = getProcessToParticipantMap(definitions);

  if (is(node, 'bpmn:Process')) {
    return processMap.get(node.id || '') || node.id || null;
  }

  const process = getOwningProcess(node);

  if (process) {
    return processMap.get(process.id || '') || process.id || null;
  }

  return null;
}

function collectAssociations(definitions: ModdleElement): ModdleElement[] {
  const associations: ModdleElement[] = [];

  function visitScope(scope: ModdleElement) {
    for (const association of ((scope.artifacts as ModdleElement[]) || [])) {
      if (is(association, 'bpmn:Association')) {
        associations.push(association);
      }
    }

    for (const element of ((scope.flowElements as ModdleElement[]) || [])) {
      if (is(element, 'bpmn:SubProcess')) {
        visitScope(element);
      }
    }
  }

  for (const root of (definitions.rootElements || [])) {
    if (isAny(root, [ 'bpmn:Process', 'bpmn:SubProcess' ])) {
      visitScope(root);
    }

    if (is(root, 'bpmn:Collaboration')) {
      for (const association of ((root.artifacts as ModdleElement[]) || [])) {
        if (is(association, 'bpmn:Association')) {
          associations.push(association);
        }
      }
    }
  }

  return associations;
}

function getAssociatedTextAnnotations(node: ModdleElement, definitions: ModdleElement): ModdleElement[] {
  const annotations: ModdleElement[] = [];

  for (const association of collectAssociations(definitions)) {
    const source = association.sourceRef;
    const target = association.targetRef;

    if (source === node && target && is(target, 'bpmn:TextAnnotation')) {
      annotations.push(target);
    }

    if (target === node && source && is(source, 'bpmn:TextAnnotation')) {
      annotations.push(source);
    }
  }

  return annotations;
}

function textFromAnnotation(annotation: ModdleElement): string {
  if (!annotation.text) {
    return '';
  }

  if (typeof annotation.text === 'string') {
    return annotation.text;
  }

  return annotation.text.body || '';
}

export function getAssociatedAnnotationTexts(node: ModdleElement, definitions: ModdleElement): string[] {
  return getAssociatedTextAnnotations(node, definitions)
    .map((annotation) => textFromAnnotation(annotation))
    .filter((text) => Boolean(text.trim()));
}

export function hasAnnotationMatching(node: ModdleElement, definitions: ModdleElement, regex: RegExp): boolean {
  return getAssociatedTextAnnotations(node, definitions).some((annotation) => regex.test(textFromAnnotation(annotation)));
}

export function hasAnyAssociatedAnnotation(node: ModdleElement, definitions: ModdleElement): boolean {
  return getAssociatedTextAnnotations(node, definitions).length > 0;
}