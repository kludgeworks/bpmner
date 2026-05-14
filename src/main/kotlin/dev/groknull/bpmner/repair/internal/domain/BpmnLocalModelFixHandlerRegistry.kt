package dev.groknull.bpmner.repair.internal.domain

import org.springframework.stereotype.Component

@Component
internal class BpmnLocalModelFixHandlerRegistry(
    handlers: List<BpmnLocalModelFixHandler>,
) {
    private val byName: Map<String, BpmnLocalModelFixHandler> =
        run {
            val duplicates =
                handlers
                    .groupBy { it.handlerName }
                    .filterValues { it.size > 1 }
                    .keys
            check(duplicates.isEmpty()) { "Duplicate LOCAL_MODEL_FIX handlerName(s): $duplicates" }
            handlers.associateBy { it.handlerName }
        }

    fun lookup(name: String): BpmnLocalModelFixHandler? = byName[name]

    fun registeredNames(): Set<String> = byName.keys
}
