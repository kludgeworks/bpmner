import { getRuleConfig, getStaticConfig } from "../rule-config";
import type {
	AutoFixContext,
	AutoFixLintIssue,
	AutoFixRuleMetadata,
	ModdleElement,
} from "./types";

export type AutoFixHandlerResult =
	| { changed: true; message: string }
	| { changed: false; message: string };

export type AutoFixHandler = (
	element: ModdleElement,
	issue: AutoFixLintIssue,
	context: AutoFixContext,
) => AutoFixHandlerResult;

export type AutoFixRegistration = {
	metadata: AutoFixRuleMetadata;
	handler?: AutoFixHandler;
};

// ─── helpers ────────────────────────────────────────────────────────────────

function removeFromArray(arr: unknown[], item: unknown): boolean {
	const idx = arr.indexOf(item);
	if (idx === -1) return false;
	arr.splice(idx, 1);
	return true;
}

function getName(element: ModdleElement): string {
	return String(
		typeof element.get === "function"
			? (element.get("name") ?? "")
			: (element.name ?? ""),
	);
}

function setName(element: ModdleElement, value: string | undefined): void {
	if (typeof element.set === "function") {
		element.set("name", value);
	} else {
		element.name = value;
	}
}

function setProp(element: ModdleElement, key: string, value: unknown): void {
	if (typeof element.set === "function") {
		element.set(key, value);
	} else {
		element[key] = value;
	}
}

// ─── attribute-mutation ──────────────────────────────────────────────────────

function clearName(element: ModdleElement): AutoFixHandlerResult {
	const current = getName(element);
	if (!current.trim()) {
		return { changed: false, message: "Element is already unnamed" };
	}
	setName(element, undefined);
	delete element.name;
	return { changed: true, message: "Cleared element name" };
}

function removeTerminateDefinition(
	element: ModdleElement,
): AutoFixHandlerResult {
	const defs = element.eventDefinitions as ModdleElement[] | undefined;
	if (!defs) {
		return { changed: false, message: "End event has no event definitions" };
	}
	const filtered = defs.filter(
		(d) => d.$type !== "bpmn:TerminateEventDefinition",
	);
	if (filtered.length === defs.length) {
		return { changed: false, message: "No TerminateEventDefinition found" };
	}
	element.eventDefinitions = filtered;
	return {
		changed: true,
		message:
			"Removed TerminateEventDefinition; end event is now a standard none end event",
	};
}

// ─── string-manipulation ────────────────────────────────────────────────────

function fixSentenceCase(element: ModdleElement): AutoFixHandlerResult {
	const raw = getName(element).trim();
	if (!raw) {
		return { changed: false, message: "Element has no name" };
	}
	const words = raw.split(/\s+/);
	const fixed = words
		.map((word, idx) => {
			if (idx === 0) {
				return word.charAt(0).toUpperCase() + word.slice(1);
			}
			if (/^[A-Z]{2,}$/.test(word)) {
				return word;
			}
			return word.charAt(0).toLowerCase() + word.slice(1);
		})
		.join(" ");
	if (fixed === raw) {
		return { changed: false, message: "Label is already in sentence case" };
	}
	setName(element, fixed);
	return {
		changed: true,
		message: `Fixed sentence case: "${raw}" → "${fixed}"`,
	};
}

function makeAbbreviationFixer(map: Record<string, string>): AutoFixHandler {
	return (element) => {
		const raw = getName(element).trim();
		if (!raw) return { changed: false, message: "Element has no name" };
		if (!Object.keys(map).length)
			return { changed: false, message: "No replacement map configured" };

		let fixed = raw;
		for (const [abbr, expansion] of Object.entries(map)) {
			fixed = fixed.replace(new RegExp(`\\b${abbr}\\b`, "g"), expansion);
		}
		if (fixed === raw)
			return {
				changed: false,
				message: "No known abbreviations found in name",
			};
		setName(element, fixed);
		return {
			changed: true,
			message: `Expanded abbreviations: "${raw}" → "${fixed}"`,
		};
	};
}

function makeTypeWordStripper(words: string[]): AutoFixHandler {
	const pattern = new RegExp(
		`\\b(${words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})\\b`,
		"gi",
	);
	return (element) => {
		const raw = getName(element).trim();
		if (!raw) return { changed: false, message: "Element has no name" };
		const fixed = raw
			.replace(pattern, "")
			.replace(/\s{2,}/g, " ")
			.trim();
		if (fixed === raw)
			return { changed: false, message: "No type words found in name" };
		if (!fixed)
			return {
				changed: false,
				message: "Stripping type words would leave an empty name; skipping",
			};
		setName(element, fixed);
		return {
			changed: true,
			message: `Stripped type words: "${raw}" → "${fixed}"`,
		};
	};
}

// ─── node-deletion ───────────────────────────────────────────────────────────

function deleteSequenceFlow(element: ModdleElement): AutoFixHandlerResult {
	if (element.$type !== "bpmn:SequenceFlow") {
		return {
			changed: false,
			message:
				"Not a sequence flow; source/target secondary reports are skipped",
		};
	}
	const source = element.sourceRef as ModdleElement | undefined;
	const target = element.targetRef as ModdleElement | undefined;
	const parent = element.$parent as ModdleElement | undefined;

	if (source?.outgoing) removeFromArray(source.outgoing as unknown[], element);
	if (target?.incoming) removeFromArray(target.incoming as unknown[], element);
	if (parent?.flowElements)
		removeFromArray(parent.flowElements as unknown[], element);

	return { changed: true, message: "Deleted duplicate sequence flow" };
}

function deleteBlankStartEvents(element: ModdleElement): AutoFixHandlerResult {
	const flowElements = element.flowElements as ModdleElement[] | undefined;
	if (!flowElements)
		return { changed: false, message: "Container has no flow elements" };

	const blanks = flowElements.filter(
		(el) =>
			el.$type === "bpmn:StartEvent" &&
			(!el.eventDefinitions || (el.eventDefinitions as unknown[]).length === 0),
	);
	if (blanks.length <= 1)
		return { changed: false, message: "At most one blank start event present" };

	const toDelete = blanks.slice(1);
	for (const se of toDelete) {
		const outgoing = [...((se.outgoing as ModdleElement[]) || [])];
		for (const flow of outgoing) {
			const tgt = flow.targetRef as ModdleElement | undefined;
			if (tgt?.incoming) removeFromArray(tgt.incoming as unknown[], flow);
			removeFromArray(flowElements, flow);
		}
		removeFromArray(flowElements, se);
	}
	return {
		changed: true,
		message: `Deleted ${toDelete.length} extra blank start event(s)`,
	};
}

function keepFirstEventDefinition(
	element: ModdleElement,
): AutoFixHandlerResult {
	const defs = element.eventDefinitions as ModdleElement[] | undefined;
	if (!defs || defs.length <= 1)
		return {
			changed: false,
			message: "Event has at most one event definition",
		};
	const removed = defs.length - 1;
	element.eventDefinitions = [defs[0]];
	return {
		changed: true,
		message: `Kept first event definition; removed ${removed} extra(s)`,
	};
}

function deleteIncomingFlows(element: ModdleElement): AutoFixHandlerResult {
	const inFlows = [...((element.incoming as ModdleElement[]) || [])];
	if (!inFlows.length)
		return { changed: false, message: "Start event has no incoming flows" };

	const parent = element.$parent as ModdleElement | undefined;
	for (const flow of inFlows) {
		const source = flow.sourceRef as ModdleElement | undefined;
		if (source?.outgoing) removeFromArray(source.outgoing as unknown[], flow);
		if (parent?.flowElements)
			removeFromArray(parent.flowElements as unknown[], flow);
	}
	element.incoming = [];
	return {
		changed: true,
		message: `Deleted ${inFlows.length} incoming flow(s) from start event`,
	};
}

// ─── ast-rewiring ────────────────────────────────────────────────────────────

function bypassGateway(element: ModdleElement): AutoFixHandlerResult {
	const incoming = element.incoming as ModdleElement[] | undefined;
	const outgoing = element.outgoing as ModdleElement[] | undefined;
	if (!incoming?.length || !outgoing?.length) {
		return { changed: false, message: "Gateway has no flows to rewire" };
	}

	const inFlow = incoming[0];
	const outFlow = outgoing[0];
	const downstream = outFlow.targetRef as ModdleElement | undefined;
	if (!downstream)
		return { changed: false, message: "Could not resolve downstream element" };

	setProp(inFlow, "targetRef", downstream);

	const downIncoming = downstream.incoming as ModdleElement[] | undefined;
	if (downIncoming) {
		const idx = downIncoming.indexOf(outFlow);
		if (idx !== -1) {
			downIncoming.splice(idx, 1, inFlow);
		} else {
			downIncoming.push(inFlow);
		}
	}

	const parent = element.$parent as ModdleElement | undefined;
	if (parent?.flowElements) {
		removeFromArray(parent.flowElements as unknown[], element);
		removeFromArray(parent.flowElements as unknown[], outFlow);
	}

	return {
		changed: true,
		message: "Removed superfluous gateway and rewired flows",
	};
}

function insertConvergingGateway(
	element: ModdleElement,
	_issue: unknown,
	ctx: AutoFixContext,
): AutoFixHandlerResult {
	const inFlows = [...((element.incoming as ModdleElement[]) || [])];
	if (inFlows.length < 2)
		return {
			changed: false,
			message: "Task does not have multiple incoming flows",
		};

	const parent = element.$parent as ModdleElement | undefined;
	if (!parent?.flowElements)
		return { changed: false, message: "Could not find parent container" };

	const flowElements = parent.flowElements as ModdleElement[];

	const newGateway = ctx.createElement("bpmn:ExclusiveGateway", {
		id: ctx.generateId(),
	});
	newGateway.$parent = parent;

	const newFlow = ctx.createElement("bpmn:SequenceFlow", {
		id: ctx.generateId(),
		sourceRef: newGateway,
		targetRef: element,
	});
	newFlow.$parent = parent;

	for (const flow of inFlows) {
		setProp(flow, "targetRef", newGateway);
	}

	newGateway.incoming = [...inFlows];
	newGateway.outgoing = [newFlow];
	element.incoming = [newFlow];

	flowElements.push(newGateway);
	flowElements.push(newFlow);

	return {
		changed: true,
		message:
			"Inserted converging gateway before task with multiple incoming flows",
	};
}

function splitJoinForkGateway(
	element: ModdleElement,
	_issue: unknown,
	ctx: AutoFixContext,
): AutoFixHandlerResult {
	const inFlows = [...((element.incoming as ModdleElement[]) || [])];
	const outFlows = [...((element.outgoing as ModdleElement[]) || [])];
	if (inFlows.length < 2 || outFlows.length < 2) {
		return {
			changed: false,
			message: "Gateway is not simultaneously a join and a fork",
		};
	}

	const parent = element.$parent as ModdleElement | undefined;
	if (!parent?.flowElements)
		return { changed: false, message: "Could not find parent container" };

	const flowElements = parent.flowElements as ModdleElement[];
	const gatewayType = element.$type as string;

	const newDiverging = ctx.createElement(gatewayType, { id: ctx.generateId() });
	newDiverging.$parent = parent;

	const connectingFlow = ctx.createElement("bpmn:SequenceFlow", {
		id: ctx.generateId(),
		sourceRef: element,
		targetRef: newDiverging,
	});
	connectingFlow.$parent = parent;

	for (const flow of outFlows) {
		setProp(flow, "sourceRef", newDiverging);
	}

	element.outgoing = [connectingFlow];
	newDiverging.incoming = [connectingFlow];
	newDiverging.outgoing = [...outFlows];

	flowElements.push(newDiverging);
	flowElements.push(connectingFlow);

	return {
		changed: true,
		message:
			"Split join-fork gateway into separate converging and diverging gateways",
	};
}

// ─── config-driven handler initialization ────────────────────────────────────

const DEFAULT_TYPE_WORDS = ["activity", "process", "event"];

function safeStaticConfig<T>(id: string, fallback: T): T {
	try {
		return getStaticConfig<T>(id) || fallback;
	} catch {
		return fallback;
	}
}

function safeReplacementMap(id: string): Record<string, string> {
	try {
		return getRuleConfig(id).replacementMap || {};
	} catch {
		return {};
	}
}

const name02Map = safeReplacementMap("name-02-uncommon-abbreviations");
const name03Words = safeStaticConfig<{ discouragedWords: string[] }>(
	"name-03-no-element-type-words",
	{ discouragedWords: DEFAULT_TYPE_WORDS },
).discouragedWords;
const data01Words = safeStaticConfig<{ discouragedWords: string[] }>(
	"data-01-no-type-words-in-data-name",
	{ discouragedWords: DEFAULT_TYPE_WORDS },
).discouragedWords;

// ─── registration table ──────────────────────────────────────────────────────

const registrations: Record<string, AutoFixRegistration> = {
	// attribute-mutation
	"bpmner/gtw-02-converging-gateway-unnamed": {
		metadata: {
			rule: "bpmner/gtw-02-converging-gateway-unnamed",
			autoFixable: true,
			fixStrategy: "attribute-mutation",
		},
		handler: clearName,
	},
	"bpmner/gtw-03-gateway-no-work-label": {
		metadata: {
			rule: "bpmner/gtw-03-gateway-no-work-label",
			autoFixable: true,
			fixStrategy: "attribute-mutation",
		},
		handler: clearName,
	},
	"superfluous-termination": {
		metadata: {
			rule: "superfluous-termination",
			autoFixable: true,
			fixStrategy: "attribute-mutation",
		},
		handler: removeTerminateDefinition,
	},

	// string-manipulation
	"bpmner/act-02-activity-label-capitalization": {
		metadata: {
			rule: "bpmner/act-02-activity-label-capitalization",
			autoFixable: true,
			fixStrategy: "string-manipulation",
		},
		handler: fixSentenceCase,
	},
	"bpmner/name-02-uncommon-abbreviations": {
		metadata: {
			rule: "bpmner/name-02-uncommon-abbreviations",
			autoFixable: true,
			fixStrategy: "string-manipulation",
			replacementMap: name02Map,
		},
		handler: makeAbbreviationFixer(name02Map),
	},
	"bpmner/name-03-no-element-type-words": {
		metadata: {
			rule: "bpmner/name-03-no-element-type-words",
			autoFixable: true,
			fixStrategy: "string-manipulation",
		},
		handler: makeTypeWordStripper(name03Words),
	},
	"bpmner/data-01-no-type-words-in-data-name": {
		metadata: {
			rule: "bpmner/data-01-no-type-words-in-data-name",
			autoFixable: true,
			fixStrategy: "string-manipulation",
		},
		handler: makeTypeWordStripper(data01Words),
	},

	// node-deletion
	"no-duplicate-sequence-flows": {
		metadata: {
			rule: "no-duplicate-sequence-flows",
			autoFixable: true,
			fixStrategy: "node-deletion",
		},
		handler: deleteSequenceFlow,
	},
	"single-blank-start-event": {
		metadata: {
			rule: "single-blank-start-event",
			autoFixable: true,
			fixStrategy: "node-deletion",
		},
		handler: deleteBlankStartEvents,
	},
	"single-event-definition": {
		metadata: {
			rule: "single-event-definition",
			autoFixable: true,
			fixStrategy: "node-deletion",
		},
		handler: keepFirstEventDefinition,
	},
	"bpmner/evt-10-start-no-incoming": {
		metadata: {
			rule: "bpmner/evt-10-start-no-incoming",
			autoFixable: true,
			fixStrategy: "node-deletion",
		},
		handler: deleteIncomingFlows,
	},

	// ast-rewiring
	"superfluous-gateway": {
		metadata: {
			rule: "superfluous-gateway",
			autoFixable: true,
			fixStrategy: "ast-rewiring",
		},
		handler: bypassGateway,
	},
	"bpmner/gtw-22-superfluous-gateway": {
		metadata: {
			rule: "bpmner/gtw-22-superfluous-gateway",
			autoFixable: true,
			fixStrategy: "ast-rewiring",
		},
		handler: bypassGateway,
	},
	"fake-join": {
		metadata: {
			rule: "fake-join",
			autoFixable: true,
			fixStrategy: "ast-rewiring",
		},
		handler: insertConvergingGateway,
	},
	"bpmner/gtw-21-fake-join": {
		metadata: {
			rule: "bpmner/gtw-21-fake-join",
			autoFixable: true,
			fixStrategy: "ast-rewiring",
		},
		handler: insertConvergingGateway,
	},
	"no-gateway-join-fork": {
		metadata: {
			rule: "no-gateway-join-fork",
			autoFixable: true,
			fixStrategy: "ast-rewiring",
		},
		handler: splitJoinForkGateway,
	},
	"bpmner/gtw-20-no-gateway-join-fork": {
		metadata: {
			rule: "bpmner/gtw-20-no-gateway-join-fork",
			autoFixable: true,
			fixStrategy: "ast-rewiring",
		},
		handler: splitJoinForkGateway,
	},
};

export function autoFixRegistration(
	rule: string,
): AutoFixRegistration | undefined {
	return registrations[rule];
}

export function autoFixMetadata(rule: string): AutoFixRuleMetadata | undefined {
	return registrations[rule]?.metadata;
}
