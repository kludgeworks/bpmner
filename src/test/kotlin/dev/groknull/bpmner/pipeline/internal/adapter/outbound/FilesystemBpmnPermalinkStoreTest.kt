/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FilesystemBpmnPermalinkStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `saves and loads XML successfully`() {
        val store = FilesystemBpmnPermalinkStore(tempDir.toString())
        val xml = "<definitions>BPMN Content</definitions>"
        val id = "employee-onboarding-1234abcd"

        store.save(id, xml)
        val loaded = store.load(id)

        assertEquals(xml, loaded)
    }

    @Test
    fun `load returns null for nonexistent id`() {
        val store = FilesystemBpmnPermalinkStore(tempDir.toString())
        assertNull(store.load("missing-id"))
    }

    @Test
    fun `save rejects path traversal and invalid IDs`() {
        val store = FilesystemBpmnPermalinkStore(tempDir.toString())
        val xml = "<definitions>BPMN Content</definitions>"

        assertThrows(IllegalArgumentException::class.java) {
            store.save("../traversal", xml)
        }

        assertThrows(IllegalArgumentException::class.java) {
            store.save("invalid_characters", xml)
        }

        assertThrows(IllegalArgumentException::class.java) {
            store.save("spaces in id", xml)
        }
    }

    @Test
    fun `load rejects invalid IDs`() {
        val store = FilesystemBpmnPermalinkStore(tempDir.toString())
        assertNull(store.load("../traversal"))
        assertNull(store.load("invalid_char"))
    }
}
