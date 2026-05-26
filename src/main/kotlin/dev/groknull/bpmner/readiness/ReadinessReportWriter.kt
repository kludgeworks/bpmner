/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.readiness

import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
fun interface ReadinessReportWriter {
    fun writeReport(
        originalInput: String,
        assessment: ProcessInputAssessment,
        outputFile: String?,
    ): String
}
