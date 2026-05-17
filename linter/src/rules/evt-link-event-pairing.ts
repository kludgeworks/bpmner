/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { is } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

function isLinkEvent(node: ModdleElement): boolean {
	return (node.eventDefinitions || []).some((def) =>
		is(def, "bpmn:LinkEventDefinition"),
	)
}

function getLinkName(node: ModdleElement): string {
	return node.name || ""
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (
			!is(node, "bpmn:IntermediateThrowEvent") &&
			!is(node, "bpmn:IntermediateCatchEvent")
		) {
			return
		}

		if (!isLinkEvent(node)) {
			return
		}

		const scope = node.$parent
		const linkName = getLinkName(node)

		if (!scope || !linkName) {
			reporter.report(
				node.id,
				"Link event must have a name and a matching pair in the same scope",
			)
			return
		}

		const peers = ((scope.flowElements as ModdleElement[]) || []).filter(
			(element) => {
				if (element === node) {
					return false
				}

				if (
					!is(element, "bpmn:IntermediateThrowEvent") &&
					!is(element, "bpmn:IntermediateCatchEvent")
				) {
					return false
				}

				return isLinkEvent(element) && getLinkName(element) === linkName
			},
		)

		const hasCounterpart = peers.some((peer) =>
			is(node, "bpmn:IntermediateThrowEvent")
				? is(peer, "bpmn:IntermediateCatchEvent")
				: is(peer, "bpmn:IntermediateThrowEvent"),
		)

		if (!hasCounterpart) {
			reporter.report(
				node.id,
				"Link event must have a named throw/catch counterpart in the same scope",
			)
		}
	}

	return { check }
}
