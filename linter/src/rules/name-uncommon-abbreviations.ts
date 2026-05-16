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
import { getRuleConfig, getRuleMessage, getStaticConfig } from "../rule-config"
import type { ModdleElement, Reporter } from "./_helpers"

const RULE_ID = "name-uncommon-abbreviations"
const TYPES_WITH_NAMES = getRuleConfig(RULE_ID).targetElements || [
	"bpmn:Task",
	"bpmn:SubProcess",
	"bpmn:CallActivity",
	"bpmn:StartEvent",
	"bpmn:IntermediateCatchEvent",
	"bpmn:IntermediateThrowEvent",
	"bpmn:EndEvent",
	"bpmn:ExclusiveGateway",
	"bpmn:InclusiveGateway",
	"bpmn:ParallelGateway",
	"bpmn:ComplexGateway",
	"bpmn:DataObjectReference",
	"bpmn:DataStoreReference",
]

const { commonAcronyms } = getStaticConfig<{ commonAcronyms: string[] }>(
	RULE_ID,
)
const COMMON_ACRONYMS = new Set(commonAcronyms)
const MESSAGE = getRuleMessage(RULE_ID)

function hasUncommonAbbreviation(name: string): boolean {
	const matches = name.match(/\b[A-Z]{2,}\b/g) || []

	return matches.some((token) => !COMMON_ACRONYMS.has(token))
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TYPES_WITH_NAMES)) {
			return
		}

		const name = node.name?.trim() || ""

		if (!name) {
			return
		}

		if (hasUncommonAbbreviation(name)) {
			reporter.report(node.id, MESSAGE)
		}
	}

	return { check }
}
