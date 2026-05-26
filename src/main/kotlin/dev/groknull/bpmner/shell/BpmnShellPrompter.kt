/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.shell

import dev.groknull.bpmner.readiness.ClarificationQuestion
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.UserInterruptException
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

fun interface BpmnShellPrompter {
    fun ask(question: ClarificationQuestion): String?
}

@Component
class StandardBpmnShellPrompter(
    @Lazy private val lineReader: LineReader,
) : BpmnShellPrompter {
    override fun ask(question: ClarificationQuestion): String? {
        lineReader.printAbove(question.questionText)
        if (question.options.isNotEmpty()) {
            question.options.forEachIndexed { index, option -> lineReader.printAbove("${index + 1}. $option") }
        }
        return try {
            lineReader.readLine("> ")
        } catch (_: UserInterruptException) {
            null
        } catch (_: EndOfFileException) {
            null
        }
    }
}
