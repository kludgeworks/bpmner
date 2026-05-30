/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.prompt

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.util.Separators
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.groknull.bpmner.repair.internal.adapter.outbound.RepairFixtures
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Regenerate `src/test/resources/prompt-baselines.json` from the canonical fixtures.
 *
 * Invoked via `bazel run //src/test:update_prompt_baselines` — Bazel sets
 * `BUILD_WORKSPACE_DIRECTORY` so the binary can write back into the source tree (the test
 * sandbox is read-only). For each measurement: if the recorded `chars` already matches,
 * the entry's `updatedBy` / `reason` metadata is preserved; otherwise the entry is
 * stamped `updatedBy = "auto"` / `reason = "regenerated <date>"` for the dev to refine
 * before committing.
 *
 * Adds missing entries automatically. Does NOT remove entries no longer in [MEASUREMENTS] —
 * those need a deliberate manual deletion, since absence usually means a probe was renamed.
 */
private const val BASELINES_PATH = "src/test/resources/prompt-baselines.json"

private const val DOC =
    "Char-count baseline for each LLM-facing payload. PromptSizeProbeTest derives ceiling " +
        "as chars × 1.15 in code; there is no manual ceiling here. On growth past ceiling: trim " +
        "the prompt. On shrinkage below 'chars': run `bazel run //src/test:update_prompt_baselines` " +
        "to rewrite this file, then commit. Each entry carries 'updatedBy' (PR or issue ref) and " +
        "'reason' for audit trail; the updater stamps 'auto' / dated 'regenerated' when an entry " +
        "changes, leaving you to refine those fields before committing."

private val MEASUREMENTS: Map<String, () -> Int> = linkedMapOf(
    "contractPrompt" to { PromptFixtures.renderContractPrompt().length },
    "contractFullPayload" to {
        val prompt = PromptFixtures.renderContractPrompt()
        (prompt + PromptFixtures.contractSchemaFormat()).length
    },
    "generationPrompt" to { PromptFixtures.renderGenerationPrompt().length },
    "generationFullPayload" to {
        val prompt = PromptFixtures.renderGenerationPrompt()
        (prompt + PromptFixtures.generationSchemaFormat()).length
    },
    "alignmentPrompt" to { PromptFixtures.renderAlignmentPrompt().length },
    "alignmentFullPayload" to {
        val prompt = PromptFixtures.renderAlignmentPrompt()
        (prompt + PromptFixtures.alignmentSchemaFormat()).length
    },
    "readinessPrompt" to { PromptFixtures.renderReadinessPrompt().length },
    "readinessFullPayload" to {
        val prompt = PromptFixtures.renderReadinessPrompt()
        (prompt + PromptFixtures.readinessSchemaFormat()).length
    },
    "repairPatchPrompt" to { RepairFixtures.renderPatchFeedback().length },
    "repairFullPrompt" to { RepairFixtures.renderFullFeedback().length },
)

fun main() {
    val path = Path.of(readWorkspace(), BASELINES_PATH)
    val mapper = jacksonObjectMapper()
    val oldBaselines = readBaselines(path, mapper)
    val (newBaselines, changes) = mergeBaselines(oldBaselines, mapper)
    writeBaselines(path, newBaselines, mapper)
    printSummary(changes)
}

private fun readWorkspace(): String = System.getenv("BUILD_WORKSPACE_DIRECTORY")
    ?: error("BUILD_WORKSPACE_DIRECTORY not set. Run via `bazel run //src/test:update_prompt_baselines`.")

private fun readBaselines(path: Path, mapper: ObjectMapper): ObjectNode {
    val root: ObjectNode = if (Files.exists(path)) {
        Files.newInputStream(path).use {
            (mapper.readTree(it) as? ObjectNode) ?: mapper.createObjectNode()
        }
    } else {
        mapper.createObjectNode()
    }
    return (root.get("baselines") as? ObjectNode) ?: mapper.createObjectNode()
}

private fun mergeBaselines(oldBaselines: ObjectNode, mapper: ObjectMapper): Pair<ObjectNode, Int> {
    val newBaselines = mapper.createObjectNode()
    // UTC so the "regenerated <date>" reason stamp doesn't differ across dev / CI timezones.
    val today = LocalDate.now(ZoneOffset.UTC).toString()
    var changes = 0
    for ((key, measure) in MEASUREMENTS) {
        val entry = updatedEntry(key, measure(), oldBaselines, mapper, today)
        if (entry.changed) changes++
        newBaselines.set<ObjectNode>(key, entry.node)
    }
    preserveOrphans(oldBaselines, newBaselines)
    return newBaselines to changes
}

private data class EntryResult(val node: ObjectNode, val changed: Boolean)

private fun updatedEntry(
    key: String,
    actual: Int,
    oldBaselines: ObjectNode,
    mapper: ObjectMapper,
    today: String,
): EntryResult {
    val existing = oldBaselines.path(key)
    val existingChars = existing.path("chars").asInt(0)
    val node = mapper.createObjectNode().apply { put("chars", actual) }

    return when {
        existingChars == actual && !existing.isMissingNode -> {
            node.put("updatedBy", existing.path("updatedBy").asText(""))
            node.put("reason", existing.path("reason").asText(""))
            println("  unchanged  $key  $actual")
            EntryResult(node, changed = false)
        }
        existingChars > 0 -> {
            node.put("updatedBy", "auto")
            node.put("reason", "regenerated $today")
            println("  changed    $key  $existingChars -> $actual")
            EntryResult(node, changed = true)
        }
        else -> {
            node.put("updatedBy", "auto")
            node.put("reason", "regenerated $today (new entry)")
            println("  added      $key  $actual")
            EntryResult(node, changed = true)
        }
    }
}

private fun preserveOrphans(oldBaselines: ObjectNode, newBaselines: ObjectNode) {
    // Entries in the JSON not present in MEASUREMENTS — surface but preserve, so a typo in
    // MEASUREMENTS doesn't silently drop a baseline.
    oldBaselines.fieldNames().forEachRemaining { key ->
        if (!MEASUREMENTS.containsKey(key)) {
            println("  orphan     $key  (preserved; remove manually if intentional)")
            newBaselines.set<ObjectNode>(key, oldBaselines.get(key))
        }
    }
}

private fun writeBaselines(path: Path, newBaselines: ObjectNode, mapper: ObjectMapper) {
    val out = mapper.createObjectNode().apply {
        put("version", 1)
        put("_doc", DOC)
        set<ObjectNode>("baselines", newBaselines)
    }
    val printer = DefaultPrettyPrinter()
        .withSeparators(
            Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER),
        )
        .apply {
            val indenter = DefaultIndenter("\t", DefaultIndenter.SYS_LF)
            indentObjectsWith(indenter)
            indentArraysWith(indenter)
        }
    Files.writeString(path, mapper.writer(printer).writeValueAsString(out) + "\n")
}

private fun printSummary(changes: Int) {
    if (changes == 0) {
        println("No changes; baseline file is up to date.")
    } else {
        println("$changes change(s) written to $BASELINES_PATH. Review 'updatedBy'/'reason' and commit.")
    }
}
