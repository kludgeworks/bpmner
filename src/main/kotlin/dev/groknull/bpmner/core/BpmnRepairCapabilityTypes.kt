package dev.groknull.bpmner.core

enum class BpmnRepairRoute { LOCAL_MODEL, LOCAL_XML, LLM, UNFIXABLE }

enum class BpmnEditSurface { BPMN_DEFINITION, BPMN_XML, NONE }

enum class BpmnRepairSafety { SAFE_AUTOMATIC, SAFE_MANUAL, LLM_ONLY }

data class BpmnLintRuleCapability(
    val id: String,
    val repairRoute: BpmnRepairRoute,
    val editSurface: BpmnEditSurface,
    val repairSafety: BpmnRepairSafety,
    val fixHandler: String?,
    val handlerExists: Boolean,
    val replacementMap: Map<String, String>?,
)
