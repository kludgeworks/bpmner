/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules.internal.domain.primitives

internal data class RequiredPropertyCheckConfig(
    val property: String,
)

internal data class PropertyPatternCheckConfig(
    val property: String,
    val pattern: String,
    val patternDescription: String? = null,
)

internal data class VocabularyCheckConfig(
    val property: String,
    val mode: VocabularyMode,
    val words: List<String>,
)

internal enum class VocabularyMode {
    REQUIRE,
    FORBID,
}

internal data class RequiredAssociationCheckConfig(
    val association: String,
    val sourceTypes: List<String> = emptyList(),
    val targetTypes: List<String> = emptyList(),
)

internal data class TopologyCheckConfig(
    val topology: TopologyMode,
    val minIncoming: Int? = null,
    val maxIncoming: Int? = null,
    val minOutgoing: Int? = null,
    val maxOutgoing: Int? = null,
)

internal enum class TopologyMode {
    NO_FAKE_JOIN,
    NO_SUPERFLUOUS,
    NO_JOIN_FORK,
    CONVERGING_UNNAMED,
}

internal data class ConnectivityCheckConfig(
    val mode: ConnectivityMode,
    val sourceTypes: List<String> = emptyList(),
    val targetTypes: List<String> = emptyList(),
)

internal enum class ConnectivityMode {
    NO_INCOMING,
    FLOWS_NAMED,
    WITHIN_POOL,
    ACROSS_POOLS,
}

internal data class PairingCheckConfig(
    val mode: PairingMode,
    val left: String? = null,
    val right: String? = null,
)

internal enum class PairingMode {
    ERROR_END_BOUNDARY,
    LINK_PAIRING,
    MESSAGE_START_FLOW,
}

internal data class CardinalityCheckConfig(
    val element: String,
    val min: Int? = null,
    val max: Int? = null,
)

internal data class PoolLabelCheckConfig(
    val mode: PoolLabelMode,
)

internal enum class PoolLabelMode {
    WHITE_BOX_NAMED_BY_PROCESS,
    BLACK_BOX_NAMED_BY_EXTERNAL_ENTITY_OR_PROCESS,
    CHILD_DIAGRAMS_KEEP_POOL_PROCESS_NAME,
    LANE_LABELS_BUSINESS_ROLES_PERFORMERS,
}

internal data class ElementConstraintCheckConfig(
    val element: String,
    val mode: ElementConstraintMode,
    val constraints: Map<String, Any?> = emptyMap(),
)

internal enum class ElementConstraintMode {
    ALLOWED_ELEMENT_SUBSET,
    TIMER_EXPRESSION,
    PARALLEL_GATEWAY_STRUCTURE,
    EVENT_BASED_GATEWAY_DIRECT_EVENTS,
}
