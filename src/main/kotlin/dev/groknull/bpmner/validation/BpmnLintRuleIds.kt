package dev.groknull.bpmner.validation

object BpmnLintRuleIds {
    private val PREFIX_REGEX = "^(klm|bpmnlint-plugin-klm)/".toRegex()

    fun bareRuleId(rule: String): String = rule.replace(PREFIX_REGEX, "")
}
