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
    val rules: Map<String, String> = emptyMap()
)

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
                val defaultRules = api.getMember("getRules").execute().`as`(Map::class.java) as Map<String, String>
                val activeRules = defaultRules + properties.rules
                logger.info("GraalJS bpmn-lint context initialized. Active rules: {}", activeRules.keys.sorted().joinToString(", "))
                logger.debug("Rule levels: {}", activeRules)
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize GraalJS bpmn-lint context", e)
        }
    }

    /**
     * Validates [bpmnXml] with bpmn-lint.
     */
    fun lint(bpmnXml: String): List<LintIssue>? {
        val api = linterApi ?: return null
        logger.debug("Starting in-process bpmn-lint validation. xmlLength={}", bpmnXml.length)
        
        return try {
            val future = CompletableFuture<String>()
            val promise = api.getMember("lintXml").execute(bpmnXml, properties.rules)
            
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

    fun destroy() {
        jsContext?.close()
    }

    companion object {
        private const val BPMNLINT_VERSION = "11.12.1"
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
