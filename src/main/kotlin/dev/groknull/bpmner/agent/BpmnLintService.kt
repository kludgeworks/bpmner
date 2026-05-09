package dev.groknull.bpmner.agent

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.springframework.core.io.ClassPathResource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties

@ConfigurationProperties("bpmner.lint")
data class BpmnLintProperties(
    val extends: List<String> = listOf("bpmnlint:recommended"),
    val rules: Map<String, String> = emptyMap()
) {
    fun toLintConfig(): RuntimeLintConfig = RuntimeLintConfig(
        extends = extends,
        rules = rules,
    )
}

data class RuntimeLintConfig(
    val extends: List<String> = emptyList(),
    val rules: Map<String, String> = emptyMap(),
)

enum class BpmnLintPhase {
    SEMANTIC_PRE_LAYOUT,
    FINAL_POST_LAYOUT,
}

class BpmnLintConfigurationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Runs bpmn-lint in-process using GraalJS and a bundled version of the BPMNER rules.
 */
@Service
@EnableConfigurationProperties(BpmnLintProperties::class)
class BpmnLintService(
    private val properties: BpmnLintProperties = BpmnLintProperties()
) {

    private val logger = LoggerFactory.getLogger(BpmnLintService::class.java)
    private val objectMapper = jacksonObjectMapper()
    private var jsContext: Context? = null
    private var linterApi: Value? = null

    @PostConstruct
    fun init() {
        try {
            logger.info("Initializing GraalJS bpmn-lint context...")
            jsContext = Context.newBuilder("js")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup { className ->
                    className == "java.util.Base64" ||
                        className == "java.lang.String" ||
                        className == "java.nio.charset.StandardCharsets" ||
                        className == "java.util.function.Consumer"
                }
                .build()

            val bundleResource = ClassPathResource("js/bpmnlint-bundle.js")
            if (!bundleResource.exists()) {
                logger.warn("bpmnlint-bundle.js not found on classpath. Linting will be unavailable until the project is built.")
                return
            }

            val bundleSource = Source.newBuilder("js", bundleResource.url).build()
            jsContext?.eval(bundleSource)
            val api = jsContext?.getBindings("js")?.getMember("BpmnLinterApi")
            linterApi = api
            
            if (api != null) {
                validateLintConfiguration(api)
                val activeRules = resolvedRules()
                logger.info("GraalJS bpmn-lint context initialized. Active rules: {}", activeRules.keys.sorted().joinToString(", "))
                logger.debug("Rule levels: {}", activeRules)
            }
        } catch (e: BpmnLintConfigurationException) {
            logger.error("Invalid BPMN lint configuration", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to initialize GraalJS bpmn-lint context", e)
        }
    }

    /**
     * Validates [bpmnXml] with bpmn-lint.
     */
    fun lint(bpmnXml: String): List<LintIssue>? = lint(bpmnXml, BpmnLintPhase.FINAL_POST_LAYOUT)

    open fun lint(bpmnXml: String, phase: BpmnLintPhase?): List<LintIssue>? {
        val resolvedPhase = phase ?: BpmnLintPhase.FINAL_POST_LAYOUT
        val api = linterApi ?: return null
        logger.debug("Starting in-process bpmn-lint validation. phase={}, xmlLength={}", resolvedPhase, bpmnXml.length)
        
        return try {
            val future = CompletableFuture<String>()
            val promise = api.getMember("lintXml").execute(bpmnXml, lintConfigJson(resolvedPhase))
            
            promise.invokeMember("then", Consumer<String> { result ->
                future.complete(result)
            })
            
            val json = future.get(10, TimeUnit.SECONDS)
            parseIssues(json)
        } catch (e: Exception) {
            logger.warn("bpmn-lint execution error: {}", e.message)
            null
        }
    }

    internal fun parseIssues(json: String): List<LintIssue> = objectMapper.readValue(json)

    open fun ruleDocs(ruleNames: Collection<String>): Map<String, String> {
        val api = linterApi ?: return emptyMap()
        if (ruleNames.isEmpty()) {
            return emptyMap()
        }

        return try {
            @Suppress("UNCHECKED_CAST")
            api.getMember("getRuleDocs")
                .execute(objectMapper.writeValueAsString(ruleNames.distinct().sorted()))
                .`as`(Map::class.java) as Map<String, String>
        } catch (e: Exception) {
            logger.warn("Failed to resolve lint rule docs: {}", e.message)
            emptyMap()
        }
    }

    open fun resolvedRules(): Map<String, String> {
        val api = linterApi ?: return emptyMap()

        return try {
            @Suppress("UNCHECKED_CAST")
            api.getMember("getRules").execute(lintConfigJson()).`as`(Map::class.java) as Map<String, String>
        } catch (e: Exception) {
            logger.warn("Failed to resolve active lint rules: {}", e.message)
            emptyMap()
        }
    }

    fun destroy() {
        jsContext?.close()
    }

    private fun validateLintConfiguration(api: Value) {
        val invalidRules = try {
            @Suppress("UNCHECKED_CAST")
            api.getMember("getInvalidRules")
                .execute(lintConfigJson())
                .`as`(List::class.java) as List<String>
        } catch (e: Exception) {
            throw BpmnLintConfigurationException(
                "Invalid BPMN lint configuration: ${e.message}",
                e,
            )
        }

        if (invalidRules.isNotEmpty()) {
            throw BpmnLintConfigurationException(
                "Invalid BPMN lint configuration: unknown rule id(s): ${invalidRules.sorted().joinToString(", ")}"
            )
        }
    }

    internal fun lintConfig(phase: BpmnLintPhase = BpmnLintPhase.FINAL_POST_LAYOUT): RuntimeLintConfig {
        val base = properties.toLintConfig()
        if (phase == BpmnLintPhase.FINAL_POST_LAYOUT) {
            return base
        }

        return base.copy(
            rules = base.rules + LAYOUT_SENSITIVE_RULES.associateWith { "off" },
        )
    }

    private fun lintConfigJson(phase: BpmnLintPhase = BpmnLintPhase.FINAL_POST_LAYOUT): String =
        objectMapper.writeValueAsString(lintConfig(phase))

    companion object {
        private const val BPMNLINT_VERSION = "11.12.1"
        private val LAYOUT_SENSITIVE_RULES = setOf(
            "no-overlapping-elements",
            "bpmnlint/no-overlapping-elements",
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class LintIssue(
    val id: String?,
    val rule: String,
    val message: String,
    val category: String = "error",
    @JsonIgnore
    val rawFields: MutableMap<String, Any?> = mutableMapOf()
) {
    @JsonAnySetter
    fun add(name: String, value: Any?) {
        if (name !in KNOWN_FIELDS) {
            rawFields[name] = value
        }
    }

    companion object {
        private val KNOWN_FIELDS = setOf("id", "rule", "message", "category")
    }
}
