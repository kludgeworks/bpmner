// This file is manually generated to avoid bpmnlint-pack-config issues with JSON imports
import adHocSubProcess from 'bpmnlint/rules/ad-hoc-sub-process';
import conditionalFlows from 'bpmnlint/rules/conditional-flows';
import endEventRequired from 'bpmnlint/rules/end-event-required';
import eventBasedGateway from 'bpmnlint/rules/event-based-gateway';
import eventSubProcessTypedStartEvent from 'bpmnlint/rules/event-sub-process-typed-start-event';
import fakeJoin from 'bpmnlint/rules/fake-join';
import globalRule from 'bpmnlint/rules/global';
import labelRequired from 'bpmnlint/rules/label-required';
import linkEvent from 'bpmnlint/rules/link-event';
import noBpmndi from 'bpmnlint/rules/no-bpmndi';
import noComplexGateway from 'bpmnlint/rules/no-complex-gateway';
import noDisconnected from 'bpmnlint/rules/no-disconnected';
import noDuplicateSequenceFlows from 'bpmnlint/rules/no-duplicate-sequence-flows';
import noGatewayJoinFork from 'bpmnlint/rules/no-gateway-join-fork';
import noImplicitSplit from 'bpmnlint/rules/no-implicit-split';
import noImplicitEnd from 'bpmnlint/rules/no-implicit-end';
import noImplicitStart from 'bpmnlint/rules/no-implicit-start';
import noInclusiveGateway from 'bpmnlint/rules/no-inclusive-gateway';
import noOverlappingElements from 'bpmnlint/rules/no-overlapping-elements';
import singleBlankStartEvent from 'bpmnlint/rules/single-blank-start-event';
import singleEventDefinition from 'bpmnlint/rules/single-event-definition';
import startEventRequired from 'bpmnlint/rules/start-event-required';
import subProcessBlankStartEvent from 'bpmnlint/rules/sub-process-blank-start-event';
import superfluousGateway from 'bpmnlint/rules/superfluous-gateway';
import superfluousTermination from 'bpmnlint/rules/superfluous-termination';

// BPMNER Rules
import act01 from '../rules/act-01-verb-object-name';
import act02 from '../rules/act-02-activity-label-capitalization';
import act03 from '../rules/act-03-discouraged-business-verbs';
import act12 from '../rules/act-12-loop-task-annotation';
import act13 from '../rules/act-13-mi-task-annotation';
import assoc01 from '../rules/assoc-01-required-annotation-association';
import data01 from '../rules/data-01-no-type-words-in-data-name';
import evt01 from '../rules/evt-01-event-state-name';
import evt02 from '../rules/evt-02-event-state-pattern';
import evt10 from '../rules/evt-10-start-no-incoming';
import evt11 from '../rules/evt-11-message-start-has-message-flow';
import evt13 from '../rules/evt-13-intermediate-event-not-action';
import evt14 from '../rules/evt-14-boundary-event-constraints';
import evt15 from '../rules/evt-15-error-end-boundary-pair';
import evt16 from '../rules/evt-16-link-event-pairing';
import flow01 from '../rules/flow-01-sequence-flow-within-pool';
import flow02 from '../rules/flow-02-diverging-flow-outcome-label';
import gen01 from '../rules/gen-01-bpmnsubset-allowed-elements';
import gen02 from '../rules/gen-02-no-duplicate-diagrams';
import gtw01 from '../rules/gtw-01-diverging-gateway-question';
import gtw02 from '../rules/gtw-02-converging-gateway-unnamed';
import gtw03 from '../rules/gtw-03-gateway-no-work-label';
import gtw11 from '../rules/gtw-11-event-based-direct-events';
import gtw12 from '../rules/gtw-12-diverging-flow-names';
import msg01 from '../rules/msg-01-message-flow-across-pools';
import msg02 from '../rules/msg-02-message-flow-name-pattern';
import name01 from '../rules/name-01-business-meaningful-label';
import name02 from '../rules/name-02-uncommon-abbreviations';
import name03 from '../rules/name-03-no-element-type-words';

export const rules: Record<string, any> = {
  'bpmnlint/ad-hoc-sub-process': adHocSubProcess,
  'ad-hoc-sub-process': adHocSubProcess,
  'bpmnlint/conditional-flows': conditionalFlows,
  'conditional-flows': conditionalFlows,
  'bpmnlint/end-event-required': endEventRequired,
  'end-event-required': endEventRequired,
  'bpmnlint/event-based-gateway': eventBasedGateway,
  'event-based-gateway': eventBasedGateway,
  'bpmnlint/event-sub-process-typed-start-event': eventSubProcessTypedStartEvent,
  'event-sub-process-typed-start-event': eventSubProcessTypedStartEvent,
  'bpmnlint/fake-join': fakeJoin,
  'fake-join': fakeJoin,
  'bpmnlint/global': globalRule,
  'global': globalRule,
  'bpmnlint/label-required': labelRequired,
  'label-required': labelRequired,
  'bpmnlint/link-event': linkEvent,
  'link-event': linkEvent,
  'bpmnlint/no-bpmndi': noBpmndi,
  'no-bpmndi': noBpmndi,
  'bpmnlint/no-complex-gateway': noComplexGateway,
  'no-complex-gateway': noComplexGateway,
  'bpmnlint/no-disconnected': noDisconnected,
  'no-disconnected': noDisconnected,
  'bpmnlint/no-duplicate-sequence-flows': noDuplicateSequenceFlows,
  'no-duplicate-sequence-flows': noDuplicateSequenceFlows,
  'bpmnlint/no-gateway-join-fork': noGatewayJoinFork,
  'no-gateway-join-fork': noGatewayJoinFork,
  'bpmnlint/no-implicit-split': noImplicitSplit,
  'no-implicit-split': noImplicitSplit,
  'bpmnlint/no-implicit-end': noImplicitEnd,
  'no-implicit-end': noImplicitEnd,
  'bpmnlint/no-implicit-start': noImplicitStart,
  'no-implicit-start': noImplicitStart,
  'bpmnlint/no-inclusive-gateway': noInclusiveGateway,
  'no-inclusive-gateway': noInclusiveGateway,
  'bpmnlint/no-overlapping-elements': noOverlappingElements,
  'no-overlapping-elements': noOverlappingElements,
  'bpmnlint/single-blank-start-event': singleBlankStartEvent,
  'single-blank-start-event': singleBlankStartEvent,
  'bpmnlint/single-event-definition': singleEventDefinition,
  'single-event-definition': singleEventDefinition,
  'bpmnlint/start-event-required': startEventRequired,
  'start-event-required': startEventRequired,
  'bpmnlint/sub-process-blank-start-event': subProcessBlankStartEvent,
  'sub-process-blank-start-event': subProcessBlankStartEvent,
  'bpmnlint/superfluous-gateway': superfluousGateway,
  'superfluous-gateway': superfluousGateway,
  'bpmnlint/superfluous-termination': superfluousTermination,
  'superfluous-termination': superfluousTermination,
  
  'bpmnlint-plugin-bpmner/act-01-verb-object-name': act01,
  'bpmner/act-01-verb-object-name': act01,
  'bpmnlint-plugin-bpmner/act-02-activity-label-capitalization': act02,
  'bpmner/act-02-activity-label-capitalization': act02,
  'bpmnlint-plugin-bpmner/act-03-discouraged-business-verbs': act03,
  'bpmner/act-03-discouraged-business-verbs': act03,
  'bpmnlint-plugin-bpmner/act-12-loop-task-annotation': act12,
  'bpmner/act-12-loop-task-annotation': act12,
  'bpmnlint-plugin-bpmner/act-13-mi-task-annotation': act13,
  'bpmner/act-13-mi-task-annotation': act13,
  'bpmnlint-plugin-bpmner/assoc-01-required-annotation-association': assoc01,
  'bpmner/assoc-01-required-annotation-association': assoc01,
  'bpmnlint-plugin-bpmner/data-01-no-type-words-in-data-name': data01,
  'bpmner/data-01-no-type-words-in-data-name': data01,
  'bpmnlint-plugin-bpmner/evt-01-event-state-name': evt01,
  'bpmner/evt-01-event-state-name': evt01,
  'bpmnlint-plugin-bpmner/evt-02-event-state-pattern': evt02,
  'bpmner/evt-02-event-state-pattern': evt02,
  'bpmnlint-plugin-bpmner/evt-10-start-no-incoming': evt10,
  'bpmner/evt-10-start-no-incoming': evt10,
  'bpmnlint-plugin-bpmner/evt-11-message-start-has-message-flow': evt11,
  'bpmner/evt-11-message-start-has-message-flow': evt11,
  'bpmnlint-plugin-bpmner/evt-13-intermediate-event-not-action': evt13,
  'bpmner/evt-13-intermediate-event-not-action': evt13,
  'bpmnlint-plugin-bpmner/evt-14-boundary-event-constraints': evt14,
  'bpmner/evt-14-boundary-event-constraints': evt14,
  'bpmnlint-plugin-bpmner/evt-15-error-end-boundary-pair': evt15,
  'bpmner/evt-15-error-end-boundary-pair': evt15,
  'bpmnlint-plugin-bpmner/evt-16-link-event-pairing': evt16,
  'bpmner/evt-16-link-event-pairing': evt16,
  'bpmnlint-plugin-bpmner/flow-01-sequence-flow-within-pool': flow01,
  'bpmner/flow-01-sequence-flow-within-pool': flow01,
  'bpmnlint-plugin-bpmner/flow-02-diverging-flow-outcome-label': flow02,
  'bpmner/flow-02-diverging-flow-outcome-label': flow02,
  'bpmnlint-plugin-bpmner/gen-01-bpmnsubset-allowed-elements': gen01,
  'bpmner/gen-01-bpmnsubset-allowed-elements': gen01,
  'bpmnlint-plugin-bpmner/gen-02-no-duplicate-diagrams': gen02,
  'bpmner/gen-02-no-duplicate-diagrams': gen02,
  'bpmnlint-plugin-bpmner/gtw-01-diverging-gateway-question': gtw01,
  'bpmner/gtw-01-diverging-gateway-question': gtw01,
  'bpmnlint-plugin-bpmner/gtw-02-converging-gateway-unnamed': gtw02,
  'bpmner/gtw-02-converging-gateway-unnamed': gtw02,
  'bpmnlint-plugin-bpmner/gtw-03-gateway-no-work-label': gtw03,
  'bpmner/gtw-03-gateway-no-work-label': gtw03,
  'bpmnlint-plugin-bpmner/gtw-11-event-based-direct-events': gtw11,
  'bpmner/gtw-11-event-based-direct-events': gtw11,
  'bpmnlint-plugin-bpmner/gtw-12-diverging-flow-names': gtw12,
  'bpmner/gtw-12-diverging-flow-names': gtw12,
  'bpmnlint-plugin-bpmner/msg-01-message-flow-across-pools': msg01,
  'bpmner/msg-01-message-flow-across-pools': msg01,
  'bpmnlint-plugin-bpmner/msg-02-message-flow-name-pattern': msg02,
  'bpmner/msg-02-message-flow-name-pattern': msg02,
  'bpmnlint-plugin-bpmner/name-01-business-meaningful-label': name01,
  'bpmner/name-01-business-meaningful-label': name01,
  'bpmnlint-plugin-bpmner/name-02-uncommon-abbreviations': name02,
  'bpmner/name-02-uncommon-abbreviations': name02,
  'bpmnlint-plugin-bpmner/name-03-no-element-type-words': name03,
  'bpmner/name-03-no-element-type-words': name03,
};

export const config = {
  rules: Object.fromEntries(Object.keys(rules).map(name => [name, name.startsWith('bpmner/act-01') || name.startsWith('bpmner/act-02') || name.startsWith('bpmner/act-03') || name.startsWith('bpmner/evt-01') || name.startsWith('bpmner/evt-02') || name.startsWith('bpmner/gtw-01') || name.startsWith('bpmner/gtw-02') || name.startsWith('bpmner/gtw-03') || name.startsWith('bpmner/evt-13') || name.startsWith('bpmner/flow-02') || name.startsWith('bpmner/msg-02') || name.startsWith('bpmner/name-01') || name.startsWith('bpmner/name-02') ? 'warn' : 'error']))
};

export const resolver = {
  resolveRule(pkg: string, name: string) {
    const combinations = [
        pkg ? `${pkg}/${name}` : name,
        name,
        `bpmnlint/${name}`,
        `bpmner/${name}`,
        `bpmnlint-plugin-bpmner/${name}`
    ];
    
    for (const key of combinations) {
        const rule = rules[key];
        if (rule) {
            return (typeof rule === 'object' && rule.default) ? rule.default : rule;
        }
    }
    return null;
  },
  resolveConfig(pkg: string, name: string) {
    if (pkg === 'bpmnlint' && name === 'recommended') {
        return config;
    }
    throw new Error(`Config resolution not supported in static resolver: ${pkg}/${name}`);
  }
};
