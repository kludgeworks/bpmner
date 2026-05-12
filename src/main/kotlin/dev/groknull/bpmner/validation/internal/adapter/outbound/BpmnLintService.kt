@file:Suppress("ReturnCount")

package dev.groknull.bpmner.validation.internal.adapter.outbound

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.groknull.bpmner.core.BpmnAutoFixResult
import dev.groknull.bpmner.core.BpmnLintPhase
import dev.groknull.bpmner.core.LintIssue
import dev.groknull.bpmner.validation.BpmnLintingPort
import jakarta.annotation.PostConstruct
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.core.io.ClassPathResource
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
@Suppress("TooManyFunctions") // one method per linting operation plus initialization and internal helpers
internal open class BpmnLintService(
    private val properties: BpmnLintProperties = BpmnLintProperties(),
    private val catalogService: RuleCatalogService,
) : BpmnLintingPort {
    private val logger = LoggerFactory.getLogger(BpmnLintService::class.java)
    private val objectMapper = jacksonObjectMapper()
    private var jsContext: Context? = null
    private var linterApi: Value? = null

    @PostConstruct
    fun init() {
        try {
            logger.info("Initializing GraalJS bpmn-lint context...")
            jsContext =
                Context
                    .newBuilder("js")
                    .allowHostAccess(HostAccess.ALL)
                    .allowHostClassLookup { className ->
                        className == "java.util.Base64" ||
                            className == "java.lang.String" ||
                            className == "java.nio.charset.StandardCharsets" ||
                            className == "java.util.function.Consumer"
                    }.build()

            val bundleResource = ClassPathResource("js/bpmnlint-bundle.js")
            if (!bundleResource.exists()) {
                logger.warn(
                    "bpmnlint-bundle.js not found on classpath. " +
                        "Linting will be unavailable until the project is built.",
                )
                return
            }

            val bundleSource = Source.newBuilder("js", bundleResource.url).build()
            jsContext?.eval(bundleSource)
            val api = jsContext?.getBindings("js")?.getMember("BpmnLinterApi")
            linterApi = api

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
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            logger.error("Failed to initialize GraalJS bpmn-lint context", e)
        }
    }

    override fun lint(
        bpmnXml: String,
        phase: BpmnLintPhase,
    ): List<LintIssue>? {
        val api = linterApi ?: return null
        logger.debug("Starting in-process bpmn-lint validation. phase={}, xmlLength={}", phase, bpmnXml.length)
        return safePolyglotCall("bpmn-lint execution error: {}") {
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
        val api = linterApi ?: return null
        val fixXml = api.getMember("fixXml") ?: return null
        logger.debug(
            "Starting in-process BPMN XML auto-fix. phase={}, xmlLength={}, issueCount={}",
            phase,
            bpmnXml.length,
            issues.size,
        )
        return safePolyglotCall("BPMN XML auto-fix execution error: {}") {
            val future = CompletableFuture<String>()
            val promise = fixXml.execute(bpmnXml, objectMapper.writeValueAsString(issues), lintConfigJson(phase))
            promise.invokeMember("then", Consumer<String> { result -> future.complete(result) })
            parseAutoFixResult(future.get(LINT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    override fun ruleDocs(ruleNames: Collection<String>): Map<String, String> {
        val api = linterApi ?: return emptyMap()
        if (ruleNames.isEmpty()) return emptyMap()
        return safePolyglotCall("Failed to resolve lint rule docs: {}") {
            @Suppress("UNCHECKED_CAST")
            api
                .getMember("getRuleDocs")
                .execute(objectMapper.writeValueAsString(ruleNames.distinct().sorted()))
                .`as`(Map::class.java) as Map<String, String>
        } ?: emptyMap()
    }

    fun resolvedRules(): Map<String, String> {
        val api = linterApi ?: return emptyMap()
        return safePolyglotCall("Failed to resolve active lint rules: {}") {
            @Suppress("UNCHECKED_CAST")
            api.getMember("getRules").execute(lintConfigJson()).`as`(Map::class.java) as Map<String, String>
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
                .associate { "bpmner/${it.id}" to if (it.severity == "error") "error" else "warn" }

        // Application.yaml (baseConfig.rules) overrides Pkl defaults
        val mergedRules = pklRules + baseConfig.rules

        val finalConfig = baseConfig.copy(rules = mergedRules)

        if (phase == BpmnLintPhase.FINAL_POST_LAYOUT) return finalConfig
        return finalConfig.copy(rules = finalConfig.rules + LAYOUT_SENSITIVE_RULES.associateWith { "off" })
    }

    private fun <T> safePolyglotCall(
        warningMessage: String,
        block: () -> T?,
    ): T? =
        try {
            block()
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            logger.warn(warningMessage, e.message)
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            logger.warn(warningMessage, e.message)
            null
        } catch (e: java.util.concurrent.TimeoutException) {
            logger.warn(warningMessage, e.message)
            null
        } catch (e: InterruptedException) {
            logger.warn(warningMessage, e.message)
            null
        } catch (e: java.util.concurrent.CancellationException) {
            logger.warn(warningMessage, e.message)
            null
        } catch (e: IllegalStateException) {
            logger.warn(warningMessage, e.message)
            null
        } catch (e: IllegalArgumentException) {
            logger.warn(warningMessage, e.message)
            null
        }

    fun destroy() {
        jsContext?.close()
    }

    private fun validateLintConfiguration(api: Value) {
        val invalidRules =
            try {
                @Suppress("UNCHECKED_CAST")
                api.getMember("getInvalidRules").execute(lintConfigJson()).`as`(List::class.java) as List<String>
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

        private val LAYOUT_SENSITIVE_RULES =
            setOf(
                "no-overlapping-elements",
                "bpmnlint/no-overlapping-elements",
            )
    }
}
