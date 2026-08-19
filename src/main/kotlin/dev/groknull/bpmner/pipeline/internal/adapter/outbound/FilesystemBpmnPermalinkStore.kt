/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound

import dev.groknull.bpmner.pipeline.BpmnPermalinkStore
import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@InfrastructureRing
@Service
internal open class FilesystemBpmnPermalinkStore(
    @Value("\${bpmner.permalink.storage-dir:}") storageDir: String = "",
) : BpmnPermalinkStore {

    private val logger = LoggerFactory.getLogger(FilesystemBpmnPermalinkStore::class.java)
    private val idRegex = Regex("^[a-z0-9-]+$")

    private val baseDir: Path = if (storageDir.isNotBlank()) {
        Paths.get(storageDir).toAbsolutePath().normalize()
    } else {
        Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
    }

    init {
        try {
            Files.createDirectories(baseDir)
            logger.info("BPMN permalinks storage directory: {}", baseDir)
        } catch (e: IOException) {
            logger.error("Failed to create permalinks storage directory: {}", baseDir, e)
        }
    }

    override fun save(id: String, xml: String) {
        require(idRegex.matches(id)) { "Invalid permalink ID: $id" }
        val filePath = baseDir.resolve("$id.bpmn").normalize()

        // Extra safeguard: verify resolved file is still under baseDir
        require(filePath.startsWith(baseDir)) { "Path traversal attempt detected: $id" }

        try {
            Files.writeString(filePath, xml, StandardCharsets.UTF_8)
            logger.debug("Persisted BPMN permalink XML to {}", filePath)
        } catch (e: IOException) {
            logger.error("Failed to save BPMN permalink XML to {}", filePath, e)
            throw e
        }
    }

    override fun load(id: String): String? {
        if (!idRegex.matches(id)) {
            logger.warn("Rejecting invalid permalink ID load request: {}", id)
            return null
        }
        val filePath = baseDir.resolve("$id.bpmn").normalize()
        if (!filePath.startsWith(baseDir)) {
            logger.warn("Path traversal/boundary escape attempt detected on load: {}", id)
            return null
        }

        if (!Files.exists(filePath)) {
            return null
        }

        return try {
            Files.readString(filePath, StandardCharsets.UTF_8)
        } catch (e: IOException) {
            logger.error("Failed to read BPMN permalink XML from {}", filePath, e)
            null
        }
    }
}
