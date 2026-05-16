/**
 * Copyright (c) 2026 The Project Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
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
