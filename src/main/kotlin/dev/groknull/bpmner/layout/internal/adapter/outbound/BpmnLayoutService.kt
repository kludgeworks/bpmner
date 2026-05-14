package dev.groknull.bpmner.layout

import jakarta.annotation.PostConstruct
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@SecondaryAdapter
@Service
internal open class BpmnLayoutService : BpmnLayoutPort {
    private val logger = LoggerFactory.getLogger(BpmnLayoutService::class.java)
    private var jsContext: Context? = null
    private var layoutApi: Value? = null

    @PostConstruct
    fun init() {
        try {
            logger.info("Initializing GraalJS layout context...")
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

            val bundleResource = ClassPathResource("js/bpmn-layout-bundle.js")
            if (!bundleResource.exists()) {
                logger.warn("bpmn-layout-bundle.js not found on classpath. Auto-layout will be unavailable.")
                return
            }

            val bundleSource = Source.newBuilder("js", bundleResource.url).build()
            jsContext?.eval(bundleSource)
            layoutApi = jsContext?.getBindings("js")?.getMember("BpmnLayoutApi")

            if (layoutApi != null) {
                logger.info("GraalJS layout context initialized successfully.")
            } else {
                logger.warn("BpmnLayoutApi not found in the JS bundle.")
            }
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            logger.error("Failed to initialize GraalJS layout context", e)
        }
    }

    override fun layout(xml: String): String {
        val api = layoutApi ?: return xml
        logger.debug("Starting in-process BPMN auto-layout. xmlLength={}", xml.length)

        return try {
            val future = CompletableFuture<String>()
            val promise = api.invokeMember("layoutXml", xml)

            promise.invokeMember(
                "then",
                Consumer<String> { result ->
                    future.complete(result)
                },
            )

            future.get(LAYOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            logger.warn("BPMN auto-layout execution error: {}", e.message)
            xml
        } catch (e: java.util.concurrent.ExecutionException) {
            logger.warn("BPMN auto-layout execution error: {}", e.message)
            xml
        } catch (e: java.util.concurrent.TimeoutException) {
            logger.warn("BPMN auto-layout execution error: {}", e.message)
            xml
        } catch (e: InterruptedException) {
            logger.warn("BPMN auto-layout execution error: {}", e.message)
            xml
        } catch (e: java.util.concurrent.CancellationException) {
            logger.warn("BPMN auto-layout execution error: {}", e.message)
            xml
        }
    }

    fun destroy() {
        jsContext?.close()
    }

    companion object {
        private const val LAYOUT_TIMEOUT_SECONDS = 30L
    }
}
