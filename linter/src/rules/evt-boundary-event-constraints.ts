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

import { is, isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!is(node, "bpmn:BoundaryEvent")) {
			return
		}

		if (
			!node.attachedToRef ||
			!isAny(node.attachedToRef, ["bpmn:Task", "bpmn:SubProcess"])
		) {
			reporter.report(
				node.id,
				"Boundary event must be attached to a task or subprocess",
			)
		}

		if ((node.incoming || []).length > 0) {
			reporter.report(
				node.id,
				"Boundary event must not have incoming sequence flow",
			)
		}

		if ((node.outgoing || []).length !== 1) {
			reporter.report(
				node.id,
				"Boundary event must have exactly one outgoing sequence flow",
			)
		}

		const hasErrorDefinition = (node.eventDefinitions || []).some((def) =>
			is(def, "bpmn:ErrorEventDefinition"),
		)

		if (hasErrorDefinition && node.cancelActivity !== true) {
			reporter.report(node.id, "Error boundary event must be interrupting")
		}
	}

	return { check }
}
