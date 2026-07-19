/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.outbound.preview

import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.nameWithoutExtension

@InfrastructureRing
@Service
internal open class ClasspathBpmnPreviewWriter(
    private val resourceLoader: (String) -> String = ::loadClasspathResource,
) : BpmnPreviewWriter {
    private val logger = LoggerFactory.getLogger(ClasspathBpmnPreviewWriter::class.java)

    override fun writePreview(bpmnPath: Path): Path {
        require(bpmnPath.exists()) { "BPMN input does not exist: $bpmnPath" }

        val bpmnXml = Files.readString(bpmnPath, StandardCharsets.UTF_8)
        logger.debug("[preview] read {} chars of BPMN XML from {}", bpmnXml.length, bpmnPath)
        val previewHtml =
            loadResource(PREVIEW_TEMPLATE_RESOURCE)
                .replace("\"$BPMN_XML_PLACEHOLDER\"", jsonStringLiteral(bpmnXml))
                .replace(PREVIEW_BUNDLE_PLACEHOLDER, inlineScriptContent(loadResource(PREVIEW_BUNDLE_RESOURCE)))

        val previewPath = previewPathFor(bpmnPath)
        Files.writeString(previewPath, previewHtml, StandardCharsets.UTF_8)
        logger.debug("[preview] wrote {} chars of preview HTML to {}", previewHtml.length, previewPath)
        return previewPath
    }

    // The preview is a throwaway view, not a saved artifact: write it into the system temp dir
    // (createTempFile uses java.io.tmpdir) and delete on JVM exit so it never pollutes the user's
    // working directory next to the .bpmn. The base name keeps it recognisable in the browser tab.
    private fun previewPathFor(bpmnPath: Path): Path {
        val previewPath = Files.createTempFile("bpmn-${bpmnPath.nameWithoutExtension}-", ".preview.html")
        previewPath.toFile().deleteOnExit()
        return previewPath
    }

    private fun loadResource(path: String): String = resourceLoader(path)

    private fun inlineScriptContent(value: String): String = value.replace(
        SCRIPT_CLOSE_TAG,
        SCRIPT_CLOSE_TAG_ESCAPED,
        ignoreCase = true,
    )

    private fun jsonStringLiteral(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003C")
                '>' -> append("\\u003E")
                '&' -> append("\\u0026")
                else -> {
                    if (char.code < CONTROL_CHARACTER_LIMIT) {
                        append("\\u")
                        append(char.code.toString(HEX_RADIX).padStart(UNICODE_ESCAPE_WIDTH, ZERO_CHAR))
                    } else {
                        append(char)
                    }
                }
            }
        }
        append('"')
    }

    private companion object {
        const val PREVIEW_TEMPLATE_RESOURCE = "preview/preview-template.html"
        const val PREVIEW_BUNDLE_RESOURCE = "preview/preview-bundle.js"
        const val BPMN_XML_PLACEHOLDER = "__BPMN_XML_JSON__"
        const val PREVIEW_BUNDLE_PLACEHOLDER = "__PREVIEW_BUNDLE_JS__"
        const val SCRIPT_CLOSE_TAG = "</script>"
        const val SCRIPT_CLOSE_TAG_ESCAPED = "<\\/script>"
        const val CONTROL_CHARACTER_LIMIT = 0x20
        const val HEX_RADIX = 16
        const val UNICODE_ESCAPE_WIDTH = 4
        const val ZERO_CHAR = '0'

        fun loadClasspathResource(path: String): String {
            val stream = ClasspathBpmnPreviewWriter::class.java.classLoader.getResourceAsStream(path)
                ?: error("Missing packaged BPMN preview resource: $path")
            return stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }
}
