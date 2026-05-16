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

import { isAny } from "bpmnlint-utils"
import type { ModdleElement, Reporter } from "./_helpers"

const GATEWAY_TYPES = [
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ParallelGateway",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, GATEWAY_TYPES)) return

		const incoming = (node.incoming || []).length
		const outgoing = (node.outgoing || []).length

		if (incoming === 1 && outgoing === 1) {
			reporter.report(
				node.id,
				"Gateway has a single incoming and single outgoing flow and can be removed",
			)
		}
	}

	return { check }
}
