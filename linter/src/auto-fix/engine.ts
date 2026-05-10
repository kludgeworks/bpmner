import BpmnModdle from 'bpmn-moddle';
import { autoFixRegistration } from './registry';
import {
  AutoFixLintIssue,
  AutoFixResult,
  ModdleElement,
  issueElementId,
  normalizeRuleId,
} from './types';

type AutoFixEngineOptions = {
  lintXml?: (xml: string, configInput?: unknown) => Promise<string>;
};

export async function fixBpmnXml(
  xml: string,
  issuesInput?: unknown,
  configInput?: unknown,
  options: AutoFixEngineOptions = {}
): Promise<AutoFixResult> {
  const result: AutoFixResult = {
    changed: false,
    xml,
    applied: [],
    skipped: [],
    errors: [],
  };

  const issues = await resolveIssues(xml, issuesInput, configInput, options);
  const moddle = new BpmnModdle();
  let rootElement: ModdleElement;

  try {
    rootElement = (await moddle.fromXML(xml)).rootElement as ModdleElement;
  } catch (err: unknown) {
    result.errors.push({
      rule: 'parse-error',
      message: err instanceof Error ? err.message : String(err),
    });
    return result;
  }

  const elementsById = indexElementsById(rootElement);

  for (const issue of issues) {
    const rule = normalizeRuleId(issue.rule);
    const registration = autoFixRegistration(rule);
    const elementId = issueElementId(issue);

    if (!registration?.metadata.autoFixable || !registration.handler) {
      result.skipped.push({
        rule,
        elementId,
        message: 'Rule is not auto-fixable',
      });
      continue;
    }

    if (!elementId) {
      result.skipped.push({
        rule,
        message: 'Diagnostic does not identify an element',
      });
      continue;
    }

    const element = elementsById.get(elementId);
    if (!element) {
      result.errors.push({
        rule,
        elementId,
        message: 'Element not found in BPMN XML',
      });
      continue;
    }

    try {
      const fixResult = registration.handler(element, issue);
      if (fixResult.changed) {
        result.applied.push({
          rule,
          elementId,
          message: fixResult.message,
        });
        result.changed = true;
      } else {
        result.skipped.push({
          rule,
          elementId,
          message: fixResult.message,
        });
      }
    } catch (err: unknown) {
      result.errors.push({
        rule,
        elementId,
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }

  if (!result.changed) {
    return result;
  }

  try {
    result.xml = (await moddle.toXML(rootElement)).xml;
  } catch (err: unknown) {
    result.errors.push({
      rule: 'serialize-error',
      message: err instanceof Error ? err.message : String(err),
    });
    result.changed = false;
    result.xml = xml;
  }

  return result;
}

async function resolveIssues(
  xml: string,
  issuesInput: unknown,
  configInput: unknown,
  options: AutoFixEngineOptions
): Promise<AutoFixLintIssue[]> {
  if (issuesInput == null) {
    if (!options.lintXml) {
      return [];
    }
    return parseIssues(await options.lintXml(xml, configInput));
  }

  return parseIssues(issuesInput);
}

function parseIssues(input: unknown): AutoFixLintIssue[] {
  const parsed = typeof input === 'string' ? JSON.parse(input) : input;
  return Array.isArray(parsed) ? parsed as AutoFixLintIssue[] : [];
}

function indexElementsById(rootElement: ModdleElement): Map<string, ModdleElement> {
  const elements = new Map<string, ModdleElement>();
  const seen = new WeakSet<object>();

  function visit(value: unknown) {
    if (!value || typeof value !== 'object') {
      return;
    }

    if (seen.has(value)) {
      return;
    }
    seen.add(value);

    if (Array.isArray(value)) {
      value.forEach(visit);
      return;
    }

    const element = value as ModdleElement;
    if (typeof element.id === 'string') {
      elements.set(element.id, element);
    }

    for (const [key, child] of Object.entries(element)) {
      if (key === '$parent' || key === '$attrs' || typeof child === 'function') {
        continue;
      }
      visit(child);
    }
  }

  visit(rootElement);
  return elements;
}
