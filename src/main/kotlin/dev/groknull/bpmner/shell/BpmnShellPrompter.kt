package dev.groknull.bpmner.shell

import dev.groknull.bpmner.core.ClarificationQuestion
import org.springframework.stereotype.Component

interface BpmnShellPrompter {
    fun ask(question: ClarificationQuestion): String?
}

@Component
class StandardBpmnShellPrompter : BpmnShellPrompter {
    override fun ask(question: ClarificationQuestion): String? {
        println(question.questionText)
        if (question.options.isNotEmpty()) {
            question.options.forEachIndexed { index, option -> println("${index + 1}. $option") }
        }
        print("> ")
        return readlnOrNull()
    }
}
