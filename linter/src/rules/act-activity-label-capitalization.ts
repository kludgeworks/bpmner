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

const TARGET_TYPES = ["bpmn:Task", "bpmn:SubProcess", "bpmn:CallActivity"]

function isAllCaps(word: string): boolean {
	return /^[A-Z0-9]+$/.test(word) && /[A-Z]/.test(word)
}

function startsWithUpper(word: string): boolean {
	return /^[A-Z]/.test(word)
}

export = () => {
	function check(node: ModdleElement, reporter: Reporter) {
		if (!isAny(node, TARGET_TYPES)) {
			return
		}

		const rawName = node.name?.trim() || ""

		if (!rawName) {
			return
		}

		const words = rawName.split(/\s+/)
		const first = words[0]

		if (!startsWithUpper(first) && !isAllCaps(first)) {
			reporter.report(
				node.id,
				"Activity label should start with a capitalized first word",
			)
			return
		}

		for (const word of words.slice(1)) {
			if (isAllCaps(word)) {
				continue
			}

			if (startsWithUpper(word)) {
				reporter.report(
					node.id,
					"Activity label should use sentence case after the first word (except acronyms/proper nouns)",
				)
				return
			}
		}
	}

	return { check }
}
