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
    fun `writes preview into the system temp dir, not beside the bpmn`(@TempDir tempDir: Path) {
        val bpmnPath = tempDir.resolve("order-process.bpmn")
        Files.writeString(bpmnPath, MINIMAL_BPMN, StandardCharsets.UTF_8)

        val previewPath = writer.writePreview(bpmnPath)

        val systemTemp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
        assertThat(previewPath.parent.toRealPath()).isEqualTo(systemTemp)
        assertThat(previewPath.parent).isNotEqualTo(bpmnPath.parent)
        assertThat(previewPath.fileName.toString())
            .startsWith("bpmn-order-process-")
            .endsWith(".preview.html")
        assertThat(previewPath).exists()
        assertThat(Files.readString(previewPath)).contains("BPMN Preview")
    }

    @Test
    fun `each call writes a distinct temp file and never mutates the bpmn`(@TempDir tempDir: Path) {
        val bpmnPath = tempDir.resolve("diagram.bpmn")
        Files.writeString(bpmnPath, MINIMAL_BPMN, StandardCharsets.UTF_8)

        val first = writer.writePreview(bpmnPath)
        val second = writer.writePreview(bpmnPath)

        assertThat(first).isNotEqualTo(second)
        assertThat(first).exists()
        assertThat(second).exists()
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

    @Test
    fun `escapes json special characters in bpmn xml`(@TempDir tempDir: Path) {
        // Covers backslash, double-quote, tab, newline, carriage-return, backspace,
        // form-feed, and a raw control character in the XML payload.
        val specialCharsXml =
            "<?xml version=\"1.0\"?>" +
                "<definitions id=\"D\">" +
                "<process id=\"P\" name=\"a\\b&quot;\t\n\r\u0008\u000C\u0001z\" />" +
                "</definitions>"
        val writer = ClasspathBpmnPreviewWriter { path ->
            when (path) {
                "preview/preview-template.html" -> TEST_TEMPLATE
                "preview/preview-bundle.js" -> ""
                else -> error("unexpected resource: $path")
            }
        }
        val bpmnPath = tempDir.resolve("special-chars.bpmn")
        Files.writeString(bpmnPath, specialCharsXml, StandardCharsets.UTF_8)

        val previewHtml = Files.readString(writer.writePreview(bpmnPath))

        // Backslash must be doubled
        assertThat(previewHtml).contains("\\\\")
        // Double-quote must be escaped as \"
        assertThat(previewHtml).contains("\\\"")
        // Tab, newline, carriage return
        assertThat(previewHtml).contains("\\t")
        assertThat(previewHtml).contains("\\n")
        assertThat(previewHtml).contains("\\r")
        // Backspace and form-feed
        assertThat(previewHtml).contains("\\b")
        assertThat(previewHtml).contains("\\f")
        // Raw control character 0x01 → \u0001
        assertThat(previewHtml).contains("\\u0001")
        // Plain ASCII letter 'z' must pass through unescaped (not become \u0000-something)
        assertThat(previewHtml).contains("z")
    }

    @Test
    fun `missing classpath resource raises clear error`(@TempDir tempDir: Path) {
        val writer = ClasspathBpmnPreviewWriter { path ->
            throw IllegalStateException("Missing packaged BPMN preview resource: $path")
        }
        val bpmnPath = tempDir.resolve("any.bpmn")
        Files.writeString(bpmnPath, MINIMAL_BPMN, StandardCharsets.UTF_8)

        assertThatThrownBy { writer.writePreview(bpmnPath) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Missing packaged BPMN preview resource")
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
