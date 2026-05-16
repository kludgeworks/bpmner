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

const DISCOURAGED_TYPES = [
	"bpmn:Choreography",
	"bpmn:ChoreographyTask",
	"bpmn:SubChoreography",
	"bpmn:CallChoreography",
	"bpmn:Conversation",
	"bpmn:ConversationLink",
	"bpmn:ConversationAssociation",
	"bpmn:Transaction",
	"bpmn:CompensateEventDefinition",
	"bpmn:EscalationEventDefinition",
]

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, DISCOURAGED_TYPES)) {
			return
		}

		reporter.report(
			node.id,
			`Element type <${node.$type}> is outside the supported BPMN subset`,
		)
	}

	return { check }
}
