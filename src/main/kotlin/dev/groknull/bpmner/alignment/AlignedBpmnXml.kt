/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.alignment

import dev.groknull.bpmner.alignment.BpmnAlignmentReport

data class AlignedBpmnXml(
    val xml: String,
    val alignmentReport: BpmnAlignmentReport,
)
