package dev.groknull.bpmner.validation

import jakarta.annotation.PostConstruct
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

@Component
internal class BpmnLintJsEngine {
    private val logger = LoggerFactory.getLogger(BpmnLintJsEngine::class.java)
    var jsContext: Context? = null
    var linterApi: Value? = null

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
            linterApi = jsContext?.getBindings("js")?.getMember("BpmnLinterApi")
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            logger.error("Failed to initialize GraalJS bpmn-lint context", e)
        }
    }

    fun <T> safePolyglotCall(
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
}
