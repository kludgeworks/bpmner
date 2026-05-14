package dev.groknull.bpmner.validation.internal.adapter.outbound

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.validation.BpmnLintingPort
import jakarta.annotation.PostConstruct
import org.graalvm.polyglot.TypeLiteral
import org.graalvm.polyglot.Value
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@ConfigurationProperties("bpmner.lint")
data class BpmnLintProperties(
    val extends: List<String> = listOf("bpmnlint:recommended"),
    val rules: Map<String, String> = emptyMap(),
) {
    fun toLintConfig(): RuntimeLintConfig = RuntimeLintConfig(extends = extends, rules = rules)
}

data class RuntimeLintConfig(
    val extends: List<String> = emptyList(),
    val rules: Map<String, String> = emptyMap(),
)

class BpmnLintConfigurationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

@SecondaryAdapter
@Service
@EnableConfigurationProperties(BpmnLintProperties::class)
internal open class BpmnLintService(
    private val properties: BpmnLintProperties = BpmnLintProperties(),
    private val catalogService: RuleCatalogService,
    private val engine: BpmnLintJsEngine,
) : BpmnLintingPort {
    private val logger = LoggerFactory.getLogger(BpmnLintService::class.java)
    private val objectMapper = jacksonObjectMapper()

    @PostConstruct
    fun init() {
        try {
            val api = engine.linterApi
            if (api != null) {
                validateLintConfiguration(api)
                val activeRules = resolvedRules()
                logger.info(
                    "GraalJS bpmn-lint context initialized. Active rules: {}",
                    activeRules.keys.sorted().joinToString(", "),
                )
                logger.debug("Rule levels: {}", activeRules)
            }
        } catch (e: BpmnLintConfigurationException) {
            logger.error("Invalid BPMN lint configuration", e)
            throw e
        }
    }

    override fun lint(
        bpmnXml: String,
        phase: BpmnLintPhase,
    ): List<LintIssue>? {
        val api = engine.linterApi ?: return null
        logger.debug("Starting in-process bpmn-lint validation. phase={}, xmlLength={}", phase, bpmnXml.length)
        return engine.safePolyglotCall("bpmn-lint execution error: {}") {
            val future = CompletableFuture<String>()
            val promise = api.getMember("lintXml").execute(bpmnXml, lintConfigJson(phase))
            promise.invokeMember("then", Consumer<String> { result -> future.complete(result) })
            parseIssues(future.get(LINT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    override fun autoFix(
        bpmnXml: String,
        issues: List<LintIssue>,
        phase: BpmnLintPhase,
    ): BpmnAutoFixResult? {
        val api = engine.linterApi ?: return null
        val fixXml = api.getMember("fixXml") ?: return null
        logger.debug(
            "Starting in-process BPMN XML auto-fix. phase={}, xmlLength={}, issueCount={}",
            phase,
            bpmnXml.length,
            issues.size,
        )
        return engine.safePolyglotCall("BPMN XML auto-fix execution error: {}") {
            val future = CompletableFuture<String>()
            val promise = fixXml.execute(bpmnXml, objectMapper.writeValueAsString(issues), lintConfigJson(phase))
            promise.invokeMember("then", Consumer<String> { result -> future.complete(result) })
            parseAutoFixResult(future.get(LINT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> {
        val api = engine.linterApi ?: return emptyMap()
        if (ruleNames.isEmpty()) return emptyMap()
        return engine.safePolyglotCall("Failed to resolve lint rule docs: {}") {
            api
                .getMember("getRuleDocs")
                .execute(objectMapper.writeValueAsString(ruleNames.distinct().sorted()))
                .`as`(STRING_MAP_TYPE)
        } ?: emptyMap()
    }

    fun resolvedRules(): Map<String, String> {
        val api = engine.linterApi ?: return emptyMap()
        return engine.safePolyglotCall("Failed to resolve active lint rules: {}") {
            api.getMember("getRules").execute(lintConfigJson()).`as`(STRING_MAP_TYPE)
        } ?: emptyMap()
    }

    internal fun parseIssues(json: String): List<LintIssue> = objectMapper.readValue(json)

    internal fun parseAutoFixResult(json: String): BpmnAutoFixResult = objectMapper.readValue(json)

    internal fun lintConfig(phase: BpmnLintPhase = BpmnLintPhase.FINAL_POST_LAYOUT): RuntimeLintConfig {
        val baseConfig = properties.toLintConfig()

        // Merge Pkl catalog defaults for rules that have TS implementation
        val pklRules =
            catalogService.catalog.rules
                .filter { it.hasTsImplementation }
                .associate { "klm/${it.id}" to if (it.severity == "error") "error" else "warn" }

        // Application.yaml (baseConfig.rules) overrides Pkl defaults
        val mergedRules = pklRules + baseConfig.rules

        val finalConfig = baseConfig.copy(rules = mergedRules)

        if (phase == BpmnLintPhase.FINAL_POST_LAYOUT) return finalConfig
        return finalConfig.copy(rules = finalConfig.rules + LAYOUT_SENSITIVE_RULES.associateWith { "off" })
    }

    fun destroy() {
        engine.destroy()
    }

    private fun validateLintConfiguration(api: Value) {
        val invalidRules =
            try {
                api.getMember("getInvalidRules").execute(lintConfigJson()).`as`(STRING_LIST_TYPE)
            } catch (e: org.graalvm.polyglot.PolyglotException) {
                throw BpmnLintConfigurationException("Invalid BPMN lint configuration: ${e.message}", e)
            }
        if (invalidRules.isNotEmpty()) {
            throw BpmnLintConfigurationException(
                "Invalid BPMN lint configuration: unknown rule id(s): ${invalidRules.sorted().joinToString(", ")}",
            )
        }
    }

    private fun lintConfigJson(phase: BpmnLintPhase = BpmnLintPhase.FINAL_POST_LAYOUT): String =
        objectMapper.writeValueAsString(lintConfig(phase))

    companion object {
        private const val LINT_TIMEOUT_SECONDS = 10L
        private val STRING_MAP_TYPE = object : TypeLiteral<Map<String, String>>() {}
        private val STRING_LIST_TYPE = object : TypeLiteral<List<String>>() {}

        private val LAYOUT_SENSITIVE_RULES =
            setOf(
                "no-overlapping-elements",
                "bpmnlint/no-overlapping-elements",
            )
    }
}
