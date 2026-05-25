/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.rules

sealed interface CheckPrimitive {
    data object RequiredPropertyCheck : CheckPrimitive
    data object PropertyPatternCheck : CheckPrimitive
    data object VocabularyCheck : CheckPrimitive
    data object RequiredAssociationCheck : CheckPrimitive
    data object TopologyCheck : CheckPrimitive
    data object ConnectivityCheck : CheckPrimitive
    data object PairingCheck : CheckPrimitive
    data object CardinalityCheck : CheckPrimitive
    data object PoolLabelCheck : CheckPrimitive
    data object ElementConstraintCheck : CheckPrimitive
    data object CompositeCheck : CheckPrimitive
    data object LlmCheckRule : CheckPrimitive
}
