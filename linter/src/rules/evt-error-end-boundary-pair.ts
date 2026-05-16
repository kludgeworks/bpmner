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
import {
	getDefinitions,
	getOwningProcess,
	type ModdleElement,
	type Reporter,
} from "./_helpers"

function getErrorRefKey(node: ModdleElement): string {
	const errorDefinition = (node.eventDefinitions || []).find((def) =>
		is(def, "bpmn:ErrorEventDefinition"),
	)
	const errorRef = errorDefinition?.errorRef as ModdleElement | undefined

	return (
		errorRef?.id ||
		errorRef?.name ||
		(errorDefinition?.name as string | undefined) ||
		node.name ||
		""
	)
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:EndEvent")) {
			return
		}

		const hasErrorDefinition = (node.eventDefinitions || []).some((def) =>
			is(def, "bpmn:ErrorEventDefinition"),
		)

		if (!hasErrorDefinition) {
			return
		}

		const scope = node.$parent

		if (!scope || !is(scope, "bpmn:SubProcess")) {
			reporter.report(
				node.id,
				"Error end event must be placed inside a subprocess",
			)
			return
		}

		const parentProcess = getOwningProcess(scope)
		const definitions = getDefinitions(node)

		if (!parentProcess || !definitions) {
			return
		}

		const expectedKey = getErrorRefKey(node)
		const matchingBoundary = (parentProcess.flowElements || []).find(
			(element) => {
				if (
					!is(element, "bpmn:BoundaryEvent") ||
					element.attachedToRef !== scope
				) {
					return false
				}

				const boundaryHasError = (element.eventDefinitions || []).some((def) =>
					is(def, "bpmn:ErrorEventDefinition"),
				)

				if (!boundaryHasError) {
					return false
				}

				return getErrorRefKey(element) === expectedKey
			},
		)

		if (!matchingBoundary) {
			reporter.report(
				node.id,
				"Error end event must match an error boundary event on its parent subprocess",
			)
		}
	}

	return { check }
}
