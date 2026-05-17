/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is, isAny } from "bpmnlint-utils"
import model from "wink-eng-lite-web-model"
import winkNLP from "wink-nlp"

export type ModdleElement = {
	id?: string
	$type?: string
	$parent?: ModdleElement | null
	rootElements?: ModdleElement[]
	participants?: ModdleElement[]
	processRef?: ModdleElement
	laneSets?: ModdleElement[]
	lanes?: ModdleElement[]
	diagrams?: ModdleElement[]
	plane?: ModdleElement
	planeElement?: ModdleElement[]
	bpmnElement?: ModdleElement
	categoryValueRef?: ModdleElement
	categoryValue?: ModdleElement[]
	messageFlows?: ModdleElement[]
	flowElements?: ModdleElement[]
	artifacts?: ModdleElement[]
	sourceRef?: ModdleElement
	targetRef?: ModdleElement
	eventDefinitions?: ModdleElement[]
	incoming?: ModdleElement[]
	outgoing?: ModdleElement[]
	attachedToRef?: ModdleElement
	loopCharacteristics?: ModdleElement
	text?: string | { body?: string }
	name?: string
	cancelActivity?: boolean
	[key: string]: unknown
}

export type Reporter = {
	report: (id: string | undefined, message: string) => void
}

const nlp = winkNLP(model)
const its = nlp.its

/**
 * Checks if the given name starts with a verb or auxiliary verb.
 */
export function startsWithVerbLike(name: string): boolean {
	const doc = nlp.readDoc(name)
	const first = doc.tokens().itemAt(0)

	if (!first) {
		return false
	}

	const pos = first.out(its.pos)
	return pos === "VERB" || pos === "AUX"
}

export function getDefinitions(node: ModdleElement): ModdleElement | null {
	let current: ModdleElement | undefined | null = node

	while (current?.$parent) {
		current = current.$parent
	}

	return current && is(current, "bpmn:Definitions") ? current : null
}

function getCollaborations(definitions: ModdleElement): ModdleElement[] {
	return (definitions.rootElements || []).filter((root) =>
		is(root, "bpmn:Collaboration"),
	)
}

export function getParticipants(definitions: ModdleElement): ModdleElement[] {
	return getCollaborations(definitions).flatMap(
		(collaboration) => (collaboration.participants as ModdleElement[]) || [],
	)
}

export function isWhiteBoxParticipant(participant: ModdleElement): boolean {
	return Boolean(participant.processRef)
}

export function isBlackBoxParticipant(participant: ModdleElement): boolean {
	return !isWhiteBoxParticipant(participant)
}

export function getMessageFlows(definitions: ModdleElement): ModdleElement[] {
	const flows: ModdleElement[] = []

	for (const collaboration of getCollaborations(definitions)) {
		flows.push(...((collaboration.messageFlows as ModdleElement[]) || []))
	}

	return flows
}

function getProcessToParticipantMap(
	definitions: ModdleElement,
): Map<string, string> {
	const map = new Map<string, string>()

	for (const participant of getParticipants(definitions)) {
		if (participant.processRef?.id && participant.id) {
			map.set(participant.processRef.id, participant.id)
		}
	}

	return map
}

export function getOwningProcess(node: ModdleElement): ModdleElement | null {
	let current: ModdleElement | undefined | null = node

	while (current) {
		if (is(current, "bpmn:Process")) {
			return current
		}

		current = current.$parent
	}

	return null
}

export function getPoolIdForNode(
	node: ModdleElement | undefined,
	definitions: ModdleElement,
): string | null {
	if (!node) {
		return null
	}

	if (is(node, "bpmn:Participant")) {
		return node.id || null
	}

	const processMap = getProcessToParticipantMap(definitions)

	if (is(node, "bpmn:Process")) {
		return processMap.get(node.id || "") || node.id || null
	}

	const process = getOwningProcess(node)

	if (process) {
		return processMap.get(process.id || "") || process.id || null
	}

	return null
}

/**
 * Returns the source and target pool IDs for a flow.
 */
export function getFlowPools(
	node: ModdleElement,
	definitions: ModdleElement,
): { sourcePool: string | null; targetPool: string | null } {
	const sourcePool = getPoolIdForNode(
		node.sourceRef as ModdleElement | undefined,
		definitions,
	)
	const targetPool = getPoolIdForNode(
		node.targetRef as ModdleElement | undefined,
		definitions,
	)

	return { sourcePool, targetPool }
}

function collectAssociations(definitions: ModdleElement): ModdleElement[] {
	const associations: ModdleElement[] = []

	function visitScope(scope: ModdleElement) {
		for (const association of (scope.artifacts as ModdleElement[]) || []) {
			if (is(association, "bpmn:Association")) {
				associations.push(association)
			}
		}

		for (const element of (scope.flowElements as ModdleElement[]) || []) {
			if (is(element, "bpmn:SubProcess")) {
				visitScope(element)
			}
		}
	}

	for (const root of definitions.rootElements || []) {
		if (isAny(root, ["bpmn:Process", "bpmn:SubProcess"])) {
			visitScope(root)
		}

		if (is(root, "bpmn:Collaboration")) {
			for (const association of (root.artifacts as ModdleElement[]) || []) {
				if (is(association, "bpmn:Association")) {
					associations.push(association)
				}
			}
		}
	}

	return associations
}

export function hasAnyAssociation(
	node: ModdleElement,
	definitions: ModdleElement,
): boolean {
	return collectAssociations(definitions).some(
		(association) =>
			association.sourceRef === node || association.targetRef === node,
	)
}

function getAssociatedTextAnnotations(
	node: ModdleElement,
	definitions: ModdleElement,
): ModdleElement[] {
	const annotations: ModdleElement[] = []

	for (const association of collectAssociations(definitions)) {
		const source = association.sourceRef
		const target = association.targetRef

		if (source === node && target && is(target, "bpmn:TextAnnotation")) {
			annotations.push(target)
		}

		if (target === node && source && is(source, "bpmn:TextAnnotation")) {
			annotations.push(source)
		}
	}

	return annotations
}

function textFromAnnotation(annotation: ModdleElement): string {
	if (!annotation.text) {
		return ""
	}

	if (typeof annotation.text === "string") {
		return annotation.text
	}

	return annotation.text.body || ""
}

export function getAssociatedAnnotationTexts(
	node: ModdleElement,
	definitions: ModdleElement,
): string[] {
	return getAssociatedTextAnnotations(node, definitions)
		.map((annotation) => textFromAnnotation(annotation))
		.filter((text) => Boolean(text.trim()))
}

export function hasAnnotationMatching(
	node: ModdleElement,
	definitions: ModdleElement,
	regex: RegExp,
): boolean {
	return getAssociatedTextAnnotations(node, definitions).some((annotation) =>
		regex.test(textFromAnnotation(annotation)),
	)
}

export function hasAnyAssociatedAnnotation(
	node: ModdleElement,
	definitions: ModdleElement,
): boolean {
	return getAssociatedTextAnnotations(node, definitions).length > 0
}
