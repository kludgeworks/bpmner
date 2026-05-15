package dev.groknull.bpmner.readiness

import dev.groknull.bpmner.readiness.ProcessInputAssessment
import org.jmolecules.architecture.hexagonal.SecondaryPort

@SecondaryPort
interface ReadinessReportWriter {
    fun writeReport(
        originalInput: String,
        assessment: ProcessInputAssessment,
        outputFile: String,
    ): String
}
