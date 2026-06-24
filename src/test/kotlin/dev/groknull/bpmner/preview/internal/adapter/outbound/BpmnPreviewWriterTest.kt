/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.preview.internal.adapter.outbound

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class BpmnPreviewWriterTest {
    private val writer = ClasspathBpmnPreviewWriter()

    @Test
    fun `writes sibling preview with deterministic name`(@TempDir tempDir: Path) {
        val bpmnPath = tempDir.resolve("order-process.bpmn")
        Files.writeString(bpmnPath, MINIMAL_BPMN, StandardCharsets.UTF_8)

        val previewPath = writer.writePreview(bpmnPath)

        assertThat(previewPath).isEqualTo(tempDir.resolve("order-process.preview.html"))
        assertThat(previewPath).exists()
        assertThat(Files.readString(previewPath)).contains("BPMN Preview")
    }

    @Test
    fun `overwrites existing preview without changing bpmn`(@TempDir tempDir: Path) {
        val bpmnPath = tempDir.resolve("diagram.bpmn")
        Files.writeString(bpmnPath, MINIMAL_BPMN, StandardCharsets.UTF_8)
        val previewPath = tempDir.resolve("diagram.preview.html")
        Files.writeString(previewPath, "stale preview", StandardCharsets.UTF_8)

        val actualPath = writer.writePreview(bpmnPath)

        assertThat(actualPath).isEqualTo(previewPath)
        assertThat(Files.readString(previewPath)).doesNotContain("stale preview")
        assertThat(Files.readString(bpmnPath)).isEqualTo(MINIMAL_BPMN)
    }

    @Test
    fun `fails clearly when bpmn input is missing`(@TempDir tempDir: Path) {
        val bpmnPath = tempDir.resolve("missing.bpmn")

        assertThatThrownBy { writer.writePreview(bpmnPath) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("BPMN input does not exist")
            .hasMessageContaining("missing.bpmn")
    }

    @Test
    fun `escapes xml safely for html and script contexts`(@TempDir tempDir: Path) {
        val trickyXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions data-name="quote &amp; amp">text </script><tag attr="&amp;" /></definitions>
        """.trimIndent()
        val bpmnPath = tempDir.resolve("unsafe.bpmn")
        Files.writeString(bpmnPath, trickyXml, StandardCharsets.UTF_8)

        val previewHtml = Files.readString(writer.writePreview(bpmnPath))

        assertThat(previewHtml).contains("\\u003C/script\\u003E")
        assertThat(previewHtml).contains("\\u0026amp;")
        assertThat(previewHtml).doesNotContain("text </script>")
        assertThat(previewHtml).doesNotContain("unpkg.com")
        assertThat(previewHtml).doesNotContain("jsdelivr.net")
    }

    @Test
    fun `loads packaged preview resources`(@TempDir tempDir: Path) {
        val bpmnPath = tempDir.resolve("packaged.bpmn")
        Files.writeString(bpmnPath, MINIMAL_BPMN, StandardCharsets.UTF_8)

        val previewHtml = Files.readString(writer.writePreview(bpmnPath))

        assertThat(previewHtml).contains("web/src/preview.ts")
        assertThat(previewHtml).contains("bpmn-preview-xml")
        assertThat(previewHtml).doesNotContain("__PREVIEW_BUNDLE_JS__")
        assertThat(previewHtml).doesNotContain("__BPMN_XML_JSON__")
    }

    @Test
    fun `escapes inline preview bundle script close tags`(@TempDir tempDir: Path) {
        val writer = ClasspathBpmnPreviewWriter { path ->
            when (path) {
                "preview/preview-template.html" -> TEST_TEMPLATE
                "preview/preview-bundle.js" -> "console.log('</script>'); console.log('</SCRIPT>')"
                else -> error("unexpected resource: $path")
            }
        }
        val bpmnPath = tempDir.resolve("script-safe.bpmn")
        Files.writeString(bpmnPath, MINIMAL_BPMN, StandardCharsets.UTF_8)

        val previewHtml = Files.readString(writer.writePreview(bpmnPath))

        assertThat(previewHtml).contains("console.log('<\\/script>'); console.log('<\\/script>')")
        assertThat(previewHtml).doesNotContain("console.log('</script>')")
        assertThat(previewHtml).doesNotContain("console.log('</SCRIPT>')")
    }

    private companion object {
        val TEST_TEMPLATE: String = """
            <script type="application/json" id="bpmn-preview-xml">
              "__BPMN_XML_JSON__"
            </script>
            <script>__PREVIEW_BUNDLE_JS__</script>
        """.trimIndent()

        val MINIMAL_BPMN: String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" id="Definitions_1">
              <process id="Process_1" />
            </definitions>
        """.trimIndent()
    }
}
