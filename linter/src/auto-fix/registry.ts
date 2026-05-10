import type { AutoFixLintIssue, AutoFixRuleMetadata, ModdleElement } from './types';

export type AutoFixHandlerResult =
  | { changed: true; message: string }
  | { changed: false; message: string };

export type AutoFixHandler = (element: ModdleElement, issue: AutoFixLintIssue) => AutoFixHandlerResult;

export type AutoFixRegistration = {
  metadata: AutoFixRuleMetadata;
  handler?: AutoFixHandler;
};

function clearName(element: ModdleElement): AutoFixHandlerResult {
  const current = typeof element.get === 'function' ? element.get('name') : element.name;
  if (!current || !String(current).trim()) {
    return {
      changed: false,
      message: 'Gateway is already unnamed',
    };
  }

  if (typeof element.set === 'function') {
    element.set('name', undefined);
  }
  delete element.name;

  return {
    changed: true,
    message: 'Cleared gateway name',
  };
}

const registrations: Record<string, AutoFixRegistration> = {
  'bpmner/gtw-02-converging-gateway-unnamed': {
    metadata: {
      rule: 'bpmner/gtw-02-converging-gateway-unnamed',
      autoFixable: true,
      fixStrategy: 'attribute-mutation',
    },
    handler: clearName,
  },
};

export function autoFixRegistration(rule: string): AutoFixRegistration | undefined {
  return registrations[rule];
}

export function autoFixMetadata(rule: string): AutoFixRuleMetadata | undefined {
  return registrations[rule]?.metadata;
}
