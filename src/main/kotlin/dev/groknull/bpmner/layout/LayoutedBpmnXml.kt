/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout

import dev.groknull.bpmner.core.BpmnDefinition

data class LayoutedBpmnXml(
    val definition: BpmnDefinition,
    val xml: String,
)
