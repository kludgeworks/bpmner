/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.bpmn

import java.time.Instant

/** Shared semantic contract for the standard KLM diagram metadata annotations. */
object DiagramMetadata {
    const val HEADER_ID = "bpmner-diagram-header"
    const val NOTES_ID = "bpmner-diagram-notes"
    const val LEGEND_ID = "bpmner-diagram-legend"

    val markerIds = setOf(HEADER_ID, NOTES_ID, LEGEND_ID)

    fun header(processName: String, processId: String): String = "$processName ($processId)"

    fun notes(processName: String, instant: Instant): String = listOf(
        "Process: $processName",
        "Author: Unknown",
        "Version: 1",
        "Created: $instant",
        "Updated: $instant",
    ).joinToString("\n")

    fun completeNotes(existing: String?, processName: String, instant: Instant): String {
        val values = labelledValues(existing)
        return listOf(
            "Process" to (values["Process"].takeUnless { it.isNullOrBlank() } ?: processName),
            "Author" to (values["Author"].takeUnless { it.isNullOrBlank() } ?: "Unknown"),
            "Version" to (values["Version"].takeUnless { it.isNullOrBlank() } ?: "1"),
            "Created" to (values["Created"].takeIf { it?.let(::isInstant) == true } ?: instant.toString()),
            "Updated" to (values["Updated"].takeIf { it?.let(::isInstant) == true } ?: instant.toString()),
        ).joinToString("\n") { (label, value) -> "$label: $value" }
    }

    fun legend(colors: DiagramStatusColors): String = listOf(
        "Draft: ${colors.draft}",
        "Proposed: ${colors.proposed}",
        "Implemented: ${colors.implemented}",
        "Out of production / To be removed: ${colors.outOfProduction}",
    ).joinToString("\n")

    fun completeLegend(existing: String?, colors: DiagramStatusColors): String {
        val values = labelledValues(existing)
        return listOf(
            "Draft" to colors.draft,
            "Proposed" to colors.proposed,
            "Implemented" to colors.implemented,
            "Out of production / To be removed" to colors.outOfProduction,
        ).joinToString("\n") { (status, color) -> "$status: ${values[status].takeUnless { it.isNullOrBlank() } ?: color}" }
    }

    fun hasValidHeader(text: String?, processName: String, processId: String): Boolean =
        text?.contains(processName) == true && text.contains(processId)

    fun hasValidNotes(text: String?): Boolean {
        val values = labelledValues(text)
        return REQUIRED_NOTE_FIELDS.all { values[it]?.isNotBlank() == true } &&
            values["Created"]?.let(::isInstant) == true &&
            values["Updated"]?.let(::isInstant) == true
    }

    fun hasValidLegend(text: String?): Boolean = text?.let {
        LEGEND_STATUSES.all { status ->
            it.lineSequence().any { line ->
                line.startsWith("$status:") && line.substringAfter(':').trim().isNotBlank()
            }
        }
    } == true

    private fun labelledValues(text: String?): Map<String, String> = text.orEmpty()
        .lineSequence()
        .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
        .toMap()

    private fun isInstant(value: String): Boolean = runCatching { Instant.parse(value) }.isSuccess

    private val REQUIRED_NOTE_FIELDS = setOf("Process", "Author", "Version", "Created", "Updated")
    private val LEGEND_STATUSES = listOf("Draft", "Proposed", "Implemented", "Out of production / To be removed")
}

data class DiagramStatusColors(
    val draft: String,
    val proposed: String,
    val implemented: String,
    val outOfProduction: String,
)
