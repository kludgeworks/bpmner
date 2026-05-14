package dev.groknull.bpmner.core

object BpmnLintRuleIds {
    private val PREFIX_REGEX = "^(bpmner|bpmnlint-plugin-bpmner)/".toRegex()

    fun bareRuleId(rule: String): String = rule.replace(PREFIX_REGEX, "")
}
