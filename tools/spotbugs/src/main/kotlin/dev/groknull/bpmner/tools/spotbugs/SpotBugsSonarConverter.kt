// Copyright 2026 The Project Contributors
// SPDX-License-Identifier: MIT

package dev.groknull.bpmner.tools.spotbugs

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

object SpotBugsSonarConverter {
    fun convert(xml: String): String {
        val document = parseXml(xml)
        val bugPatterns = document.elements("BugPattern").associateBy { it.attr("type") }
        val issues = document.elements("BugInstance").mapNotNull { it.toIssue() }
        val rules = issues
            .map { it.ruleId }
            .distinct()
            .sorted()
            .map { ruleId -> ruleFor(ruleId, issues.first { it.ruleId == ruleId }, bugPatterns[ruleId]) }

        return buildJson(rules, issues)
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance()
        .apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        .newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))

    private fun Element.toIssue(): SonarIssue? {
        val sourceLine = elements("SourceLine")
            .mapNotNull { sourceLine ->
                val sourcePath = sourceLine.sonarSourcePath() ?: return@mapNotNull null
                val startLine = sourceLine.startLine() ?: return@mapNotNull null
                SourceLocation(sourceLine, sourcePath, startLine)
            }
            .sortedByDescending { it.element.attr("primary") == "true" }
            .firstOrNull()
            ?: return null
        val priority = attr("priority").toIntOrNull() ?: 3
        val mapping = attr("category").toSonarType()
        return SonarIssue(
            ruleId = attr("type"),
            filePath = sourceLine.filePath,
            startLine = sourceLine.startLine,
            endLine = sourceLine.element.attr("end").toIntOrNull()?.takeIf { it >= sourceLine.startLine } ?: sourceLine.startLine,
            message = childText("LongMessage").ifBlank {
                childText("ShortMessage").ifBlank { "SpotBugs ${attr("type")} finding" }
            },
            type = mapping.type,
            legacySeverity = priority.toLegacySeverity(),
            softwareQuality = mapping.softwareQuality,
            impactSeverity = priority.toImpactSeverity(),
        )
    }

    private fun ruleFor(ruleId: String, issue: SonarIssue, pattern: Element?): SonarRule {
        return SonarRule(
            id = ruleId,
            name = pattern?.childText("ShortDescription")?.ifBlank { ruleId } ?: ruleId,
            description = pattern?.childText("Details")?.ifBlank {
                pattern.childText("LongDescription").ifBlank { "SpotBugs rule $ruleId" }
            } ?: "SpotBugs rule $ruleId",
            type = issue.type,
            legacySeverity = issue.legacySeverity,
            softwareQuality = issue.softwareQuality,
            impactSeverity = issue.impactSeverity,
        )
    }

    private fun buildJson(rules: List<SonarRule>, issues: List<SonarIssue>): String = buildString {
        append("{\n  \"rules\": [")
        rules.forEachIndexed { index, rule ->
            if (index > 0) append(",")
            append(
                """

    {
      "id": "${rule.id.json()}",
      "name": "${rule.name.json()}",
      "description": "${rule.description.json()}",
      "engineId": "spotbugs",
      "cleanCodeAttribute": "LOGICAL",
      "type": "${rule.type}",
      "severity": "${rule.legacySeverity}",
      "impacts": [
        {
          "softwareQuality": "${rule.softwareQuality}",
          "severity": "${rule.impactSeverity}"
        }
      ]
    }""",
            )
        }
        append("\n  ],\n  \"issues\": [")
        issues.forEachIndexed { index, issue ->
            if (index > 0) append(",")
            append(
                """

    {
      "ruleId": "${issue.ruleId.json()}",
      "effortMinutes": 20,
      "primaryLocation": {
        "message": "${issue.message.json()}",
        "filePath": "${issue.filePath.json()}",
        "textRange": {
          "startLine": ${issue.startLine},
          "endLine": ${issue.endLine}
        }
      }
    }""",
            )
        }
        append("\n  ]\n}\n")
    }

    private fun Element.startLine(): Int? {
        return attr("start").toIntOrNull()?.takeIf { it > 0 }
            ?: attr("startLine").toIntOrNull()?.takeIf { it > 0 }
    }

    private fun Element.sonarSourcePath(): String? {
        val sourcePath = attr("sourcepath")
        return when {
            sourcePath.startsWith("src/main/kotlin/") && sourcePath.endsWith(".kt") -> sourcePath
            sourcePath.startsWith("dev/groknull/bpmner/") && sourcePath.endsWith(".kt") -> "src/main/kotlin/$sourcePath"
            else -> null
        }
    }

    private fun String.toSonarType(): TypeMapping = when (this) {
        "SECURITY" -> TypeMapping("VULNERABILITY", "SECURITY")
        "CORRECTNESS", "MT_CORRECTNESS", "MALICIOUS_CODE", "EXPERIMENTAL" -> TypeMapping("BUG", "RELIABILITY")
        else -> TypeMapping("CODE_SMELL", "MAINTAINABILITY")
    }

    private fun Int.toLegacySeverity(): String = when (this) {
        1 -> "CRITICAL"
        2 -> "MAJOR"
        else -> "MINOR"
    }

    private fun Int.toImpactSeverity(): String = when (this) {
        1 -> "HIGH"
        2 -> "MEDIUM"
        else -> "LOW"
    }

    private fun Element.childText(tagName: String): String = elements(tagName).firstOrNull()?.textContent?.trim().orEmpty()

    private fun Element.attr(name: String): String = getAttribute(name).orEmpty()

    private fun org.w3c.dom.Document.elements(tagName: String): List<Element> = documentElement.elements(tagName)

    private fun Element.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun String.json(): String = buildString {
        this@json.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
        }
    }

    private data class TypeMapping(
        val type: String,
        val softwareQuality: String,
    )

    private data class SourceLocation(
        val element: Element,
        val filePath: String,
        val startLine: Int,
    )

    private data class SonarRule(
        val id: String,
        val name: String,
        val description: String,
        val type: String,
        val legacySeverity: String,
        val softwareQuality: String,
        val impactSeverity: String,
    )

    private data class SonarIssue(
        val ruleId: String,
        val filePath: String,
        val startLine: Int,
        val endLine: Int,
        val message: String,
        val type: String,
        val legacySeverity: String,
        val softwareQuality: String,
        val impactSeverity: String,
    )
}
