import type { RuleConfig } from "../rule-config"
import { getRuleConfig, getStaticConfig } from "../rule-config"
import type {
	AutoFixContext,
	AutoFixLintIssue,
	AutoFixRuleMetadata,
	ModdleElement,
} from "./types"

export type AutoFixHandlerResult =
	| { changed: true; message: string }
	| { changed: false; message: string }

export type AutoFixHandler = (
	element: ModdleElement,
	issue: AutoFixLintIssue,
	context: AutoFixContext,
) => AutoFixHandlerResult

export type AutoFixRegistration = {
	metadata: AutoFixRuleMetadata
	handler?: AutoFixHandler
}

// ─── helpers ────────────────────────────────────────────────────────────────

function removeFromArray(arr: unknown[], item: unknown): boolean {
	const idx = arr.indexOf(item)
	if (idx === -1) return false
	arr.splice(idx, 1)
	return true
}

function getName(element: ModdleElement): string {
	return String(
		typeof element.get === "function"
			? (element.get("name") ?? "")
			: (element.name ?? ""),
	)
}

function setName(element: ModdleElement, value: string | undefined): void {
	if (typeof element.set === "function") {
		element.set("name", value)
	} else {
		element.name = value
	}
}

// ─── handlers ───────────────────────────────────────────────────────────────

/**
 * attribute-mutation: Clear the element name.
 */
function clearName(element: ModdleElement): AutoFixHandlerResult {
	const current = getName(element)
	if (!current.trim()) {
		return { changed: false, message: "Element is already unnamed" }
	}
	setName(element, undefined)
	delete element.name
	return { changed: true, message: "Cleared element name" }
}

/**
 * attribute-mutation: Remove TerminateEventDefinition from an end event.
 */
function removeTerminateDefinition(
	element: ModdleElement,
): AutoFixHandlerResult {
	const defs = element.eventDefinitions as ModdleElement[] | undefined
	if (!defs) {
		return { changed: false, message: "End event has no event definitions" }
	}
	const filtered = defs.filter(
		(d) => d.$type !== "bpmn:TerminateEventDefinition",
	)
	if (filtered.length === defs.length) {
		return { changed: false, message: "No TerminateEventDefinition found" }
	}
	element.eventDefinitions = filtered
	return {
		changed: true,
		message:
			"Removed TerminateEventDefinition; end event is now a standard none end event",
	}
}

/**
 * string-manipulation: Fix label to sentence case.
 */
function fixSentenceCase(element: ModdleElement): AutoFixHandlerResult {
	const raw = getName(element).trim()
	if (!raw) {
		return { changed: false, message: "Element has no name" }
	}
	const words = raw.split(/\s+/)
	const fixed = words
		.map((word, idx) => {
			if (idx === 0) {
				return word.charAt(0).toUpperCase() + word.slice(1)
			}
			if (/^[A-Z]{2,}$/.test(word)) {
				return word
			}
			return word.charAt(0).toLowerCase() + word.slice(1)
		})
		.join(" ")
	if (fixed === raw) {
		return { changed: false, message: "Label is already in sentence case" }
	}
	setName(element, fixed)
	return {
		changed: true,
		message: `Fixed sentence case: "${raw}" → "${fixed}"`,
	}
}

/**
 * string-manipulation: Expand abbreviations based on replacementMap.
 */
function expandAbbreviations(
	element: ModdleElement,
	issue: AutoFixLintIssue,
): AutoFixHandlerResult {
	const ruleId = issue.rule.replace(/^bpmner\//, "")
	const map = getRuleConfig(ruleId).repair.replacementMap || {}
	const raw = getName(element).trim()
	if (!raw) return { changed: false, message: "Element has no name" }
	if (!Object.keys(map).length)
		return { changed: false, message: "No replacement map configured" }

	let fixed = raw
	for (const [abbr, expansion] of Object.entries(map)) {
		fixed = fixed.replace(new RegExp(`\\b${abbr}\\b`, "g"), expansion)
	}
	if (fixed === raw)
		return {
			changed: false,
			message: "No known abbreviations found in name",
		}
	setName(element, fixed)
	return {
		changed: true,
		message: `Expanded abbreviations: "${raw}" → "${fixed}"`,
	}
}

/**
 * string-manipulation: Strip discouraged type words.
 */
function stripTypeWords(
	element: ModdleElement,
	issue: AutoFixLintIssue,
): AutoFixHandlerResult {
	const ruleId = issue.rule.replace(/^bpmner\//, "")
	const config = getStaticConfig<{ discouragedWords: string[] }>(ruleId) || {
		discouragedWords: ["activity", "process", "event"],
	}
	const words = config.discouragedWords
	const pattern = new RegExp(
		`\\b(${words.map((w) => w.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|")})\\b`,
		"gi",
	)

	const raw = getName(element).trim()
	if (!raw) return { changed: false, message: "Element has no name" }
	const fixed = raw
		.replace(pattern, "")
		.replace(/\s{2,}/g, " ")
		.trim()
	if (fixed === raw)
		return { changed: false, message: "No type words found in name" }
	if (!fixed)
		return {
			changed: false,
			message: "Stripping type words would leave an empty name; skipping",
		}
	setName(element, fixed)
	return {
		changed: true,
		message: `Stripped type words: "${raw}" → "${fixed}"`,
	}
}

/**
 * node-deletion: Delete duplicate sequence flow.
 */
function deleteSequenceFlow(element: ModdleElement): AutoFixHandlerResult {
	if (element.$type !== "bpmn:SequenceFlow") {
		return {
			changed: false,
			message:
				"Not a sequence flow; source/target secondary reports are skipped",
		}
	}
	const source = element.sourceRef as ModdleElement | undefined
	const target = element.targetRef as ModdleElement | undefined
	const parent = element.$parent as ModdleElement | undefined

	if (source?.outgoing) removeFromArray(source.outgoing as unknown[], element)
	if (target?.incoming) removeFromArray(target.incoming as unknown[], element)
	if (parent?.flowElements)
		removeFromArray(parent.flowElements as unknown[], element)

	return { changed: true, message: "Deleted duplicate sequence flow" }
}

/**
 * node-deletion: Delete redundant blank start events.
 */
function deleteBlankStartEvents(element: ModdleElement): AutoFixHandlerResult {
	const flowElements = element.flowElements as ModdleElement[] | undefined
	if (!flowElements)
		return { changed: false, message: "Container has no flow elements" }

	const blanks = flowElements.filter(
		(el) =>
			el.$type === "bpmn:StartEvent" &&
			(!el.eventDefinitions || (el.eventDefinitions as unknown[]).length === 0),
	)
	if (blanks.length <= 1)
		return { changed: false, message: "At most one blank start event present" }

	const toDelete = blanks.slice(1)
	for (const se of toDelete) {
		const outgoing = [...((se.outgoing as ModdleElement[]) || [])]
		for (const flow of outgoing) {
			const tgt = flow.targetRef as ModdleElement | undefined
			if (tgt?.incoming) removeFromArray(tgt.incoming as unknown[], flow)
			removeFromArray(flowElements, flow)
		}
		removeFromArray(flowElements, se)
	}
	return {
		changed: true,
		message: `Deleted ${toDelete.length} extra blank start event(s)`,
	}
}

/**
 * node-deletion: Keep only the first event definition.
 */
function keepFirstEventDefinition(
	element: ModdleElement,
): AutoFixHandlerResult {
	const defs = element.eventDefinitions as ModdleElement[] | undefined
	if (!defs || defs.length <= 1)
		return { changed: false, message: "Event has at most one event definition" }
	const removed = defs.length - 1
	element.eventDefinitions = [defs[0]]
	return {
		changed: true,
		message: `Kept first event definition; removed ${removed} extra(s)`,
	}
}

/**
 * node-deletion: Delete incoming sequence flows from a start event.
 */
function deleteIncomingFlows(element: ModdleElement): AutoFixHandlerResult {
	const inFlows = [...((element.incoming as ModdleElement[]) || [])]
	if (!inFlows.length)
		return { changed: false, message: "Start event has no incoming flows" }

	const parent = element.$parent as ModdleElement | undefined
	for (const flow of inFlows) {
		const source = flow.sourceRef as ModdleElement | undefined
		if (source?.outgoing) removeFromArray(source.outgoing as unknown[], flow)
		if (parent?.flowElements)
			removeFromArray(parent.flowElements as unknown[], flow)
	}
	element.incoming = []
	return {
		changed: true,
		message: `Deleted ${inFlows.length} incoming flow(s) from start event`,
	}
}

// ─── registration ───────────────────────────────────────────────────────────

const HANDLERS: Record<string, AutoFixHandler> = {
	clearName,
	removeTerminateDefinition,
	fixSentenceCase,
	expandAbbreviations,
	stripTypeWords,
	deleteSequenceFlow,
	deleteBlankStartEvents,
	keepFirstEventDefinition,
	deleteIncomingFlows,
}

const HANDLER_STRATEGIES: Record<string, AutoFixRuleMetadata["fixStrategy"]> = {
	clearName: "attribute-mutation",
	removeTerminateDefinition: "attribute-mutation",
	fixSentenceCase: "string-manipulation",
	expandAbbreviations: "string-manipulation",
	stripTypeWords: "string-manipulation",
	deleteSequenceFlow: "node-deletion",
	deleteBlankStartEvents: "node-deletion",
	keepFirstEventDefinition: "node-deletion",
	deleteIncomingFlows: "node-deletion",
}

export function hasHandler(name: string): boolean {
	return name in HANDLERS
}

export function autoFixRegistration(
	ruleId: string,
): AutoFixRegistration | undefined {
	const normalizedId = ruleId.replace(/^bpmner\//, "")
	let config: RuleConfig
	try {
		config = getRuleConfig(normalizedId)
	} catch {
		// Fallback for non-BPMNER rules or manual registrations
		if (ruleId === "superfluous-termination") {
			return {
				metadata: {
					rule: ruleId,
					autoFixable: true,
					fixStrategy: "attribute-mutation",
				},
				handler: removeTerminateDefinition,
			}
		}
		if (ruleId === "no-duplicate-sequence-flows") {
			return {
				metadata: {
					rule: ruleId,
					autoFixable: true,
					fixStrategy: "node-deletion",
				},
				handler: deleteSequenceFlow,
			}
		}
		if (ruleId === "single-blank-start-event") {
			return {
				metadata: {
					rule: ruleId,
					autoFixable: true,
					fixStrategy: "node-deletion",
				},
				handler: deleteBlankStartEvents,
			}
		}
		if (ruleId === "single-event-definition") {
			return {
				metadata: {
					rule: ruleId,
					autoFixable: true,
					fixStrategy: "node-deletion",
				},
				handler: keepFirstEventDefinition,
			}
		}
		return undefined
	}

	const { kind, handler: handlerName, replacementMap } = config.repair
	if (
		kind === "UNFIXABLE" ||
		kind === "LLM_MODEL_PATCH" ||
		kind === "LLM_XML_REWRITE" ||
		!handlerName
	) {
		return undefined
	}

	const handler = HANDLERS[handlerName]
	if (!handler) {
		console.error(
			`No JS handler found for handler: ${handlerName} (rule: ${ruleId})`,
		)
		return undefined
	}

	return {
		metadata: {
			rule: ruleId,
			autoFixable: true,
			fixStrategy: HANDLER_STRATEGIES[handlerName] ?? "ast-rewiring",
			fixMethod: handlerName,
			replacementMap: replacementMap ?? undefined,
		},
		handler,
	}
}

export function autoFixMetadata(
	ruleId: string,
): AutoFixRuleMetadata | undefined {
	return autoFixRegistration(ruleId)?.metadata
}
