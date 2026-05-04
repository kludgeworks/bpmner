package dev.groknull.bpmner.agent

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Runs bpmn-lint through npx using the upstream Node.js packages directly.
 */
@Service
class BpmnLintService {

    private val logger = LoggerFactory.getLogger(BpmnLintService::class.java)
    private val objectMapper = jacksonObjectMapper()

    @PostConstruct
    fun init() {
        logger.info("bpmn-lint configured to run via npx")
    }

    /**
     * Validates [bpmnXml] with bpmn-lint.
     *
     * Returns a non-empty list of issues when the diagram has problems,
     * an empty list when it passes, or null when bpmnlint execution is unavailable.
     */
    fun lint(bpmnXml: String): List<LintIssue>? {
        logger.debug("Starting bpmn-lint validation via npx. xmlLength={}", bpmnXml.length)
        return try {
            val issues = runLintWithNpx(bpmnXml)
            logger.debug("bpmn-lint completed. issueCount={}", issues.size)
            issues
        } catch (e: Exception) {
            logger.warn("bpmn-lint execution error: {}", e.message)
            null
        }
    }

    private fun runLintWithNpx(bpmnXml: String): List<LintIssue> {
        val process = ProcessBuilder(
            "npx",
            "--yes",
            "--package=bpmnlint@$BPMNLINT_VERSION",
            "-c",
            "BPMNLINT_PACKAGE_JSON=\"\$(dirname \"\$(command -v bpmnlint)\")/../bpmnlint/package.json\" " +
                "node --input-type=module --eval \"$ESCAPED_NODE_LINT_SCRIPT\""
        )
            .redirectErrorStream(true)
            .start()

        process.outputStream.bufferedWriter().use { it.write(bpmnXml) }

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException(output.trim().ifBlank { "node exited with code $exitCode" })
        }

        return objectMapper.readValue(output)
    }

    fun destroy() {
        // Retained for test compatibility; npx execution has no local resources to clean up.
    }

    companion object {
        private const val BPMNLINT_VERSION = "11.12.1"

        private val NODE_LINT_SCRIPT = """
            import { createRequire } from 'node:module';
            import { readFileSync } from 'node:fs';

            const xml = readFileSync(0, 'utf8');
            const require = createRequire(process.env.BPMNLINT_PACKAGE_JSON);

            const bpmnlintModule = require('bpmnlint');
            const Linter = bpmnlintModule.Linter || bpmnlintModule.default || bpmnlintModule;

            const nodeResolverModule = require('bpmnlint/lib/resolver/node-resolver');
            const NodeResolver = nodeResolverModule.default || nodeResolverModule;

            const { BpmnModdle } = require('bpmn-moddle');

            const moddle = new BpmnModdle();
            const parsed = await moddle.fromXML(xml);

            const linter = new Linter({
              config: { extends: 'bpmnlint:recommended' },
              resolver: new NodeResolver()
            });

            const result = await linter.lint(parsed.rootElement);
            const issues = [];

            for (const [ rule, reports ] of Object.entries(result || {})) {
              for (const item of reports || []) {
                issues.push({
                  rule,
                  message: item.message,
                  category: item.category || 'error'
                });
              }
            }

            process.stdout.write(JSON.stringify(issues));
        """.trimIndent()
        private val ESCAPED_NODE_LINT_SCRIPT = NODE_LINT_SCRIPT.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LintIssue(
    val rule: String,
    val message: String,
    val category: String = "error",
)
