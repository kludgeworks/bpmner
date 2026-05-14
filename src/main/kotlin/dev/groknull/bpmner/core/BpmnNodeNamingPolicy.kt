package dev.groknull.bpmner.core

object BpmnNodeNamingPolicy {
    fun normalize(name: String?): String? = name?.takeIf { it.isNotBlank() }

    fun requiresName(
        node: BpmnNode,
        outgoingCount: Int,
    ): Boolean =
        when (node.type) {
            NodeType.START_EVENT,
            NodeType.USER_TASK,
            NodeType.SERVICE_TASK,
            NodeType.END_EVENT,
            -> true

            NodeType.EXCLUSIVE_GATEWAY -> outgoingCount > 1
        }

    fun missingNameMessage(node: BpmnNode): String {
        val nodeId = node.id.ifBlank { "<blank>" }
        return "node $nodeId name must not be blank for ${node.type}"
    }
}
